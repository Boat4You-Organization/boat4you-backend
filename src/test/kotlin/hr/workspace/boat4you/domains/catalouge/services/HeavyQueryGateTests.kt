package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException.Reason
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.10.2026 (Codex audit F2): bursts of cold landing pages held up to 22 of the 35 Hikari
 * connections in `/public/yachts/distribution` and the boat pages failed. [HeavyQueryGate] is the
 * bound in front of those reads, so these tests pin what it must hold: the cap is real, a full
 * gate answers fast instead of parking the caller, the queue is bounded, and a failing query gives
 * its slot back. The shed request reaches the client as 503 + Retry-After.
 */
class HeavyQueryGateTests {
    @Test
    fun `at most permits queries run at once`() {
        val permits = 2
        val callers = 8
        val gate = HeavyQueryGate(HeavyQuery.SEARCH_LIST, permits, waitMs = 10_000)
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val served = AtomicInteger()
        // Nobody leaves before `permits` callers are inside together, so the peak is a fact
        // rather than a scheduling race (same recipe as YachtImageResizeGateTests).
        val together = CountDownLatch(permits)

        val threads =
            (1..callers).map {
                thread {
                    gate.withSlot {
                        val now = inside.incrementAndGet()
                        peak.getAndUpdate { maxOf(it, now) }
                        together.countDown()
                        together.await(10, TimeUnit.SECONDS)
                        inside.decrementAndGet()
                    }
                    served.incrementAndGet()
                }
            }
        threads.forEach { it.join(30_000) }

        assertEquals(callers, served.get(), "every caller should eventually be served, not shed")
        assertTrue(peak.get() <= permits, "the gate let ${peak.get()} queries run at once, cap is $permits")
        assertTrue(peak.get() >= permits, "only ${peak.get()} of $permits slots were ever used at once")
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `a full gate sheds the next caller with SATURATED after the short wait`() {
        val gate = HeavyQueryGate(HeavyQuery.DISTRIBUTION, permits = 1, waitMs = 100)
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                gate.withSlot {
                    holderInside.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        val shed = assertFailsWith<HeavyQueryBusyException> { gate.withSlot { "never runs" } }
        val shedAfterMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(HeavyQuery.DISTRIBUTION, shed.query)
        assertEquals(Reason.SATURATED, shed.reason)
        // Fast: the 100 ms wait, not the 20 s the holder could take.
        assertTrue(shedAfterMs < 2_000, "the caller was parked for $shedAfterMs ms instead of shed")

        release.countDown()
        holder.join(5_000)
        assertEquals("ran", gate.withSlot { "ran" }, "the slot must be free again once the holder left")
    }

    @Test
    fun `a queue deeper than the waiter bound is shed immediately`() {
        val permits = 1
        val bound = permits * HeavyQueryGate.MAX_WAITERS_PER_PERMIT
        val gate = HeavyQueryGate(HeavyQuery.SEARCH_LIST, permits, waitMs = 30_000)
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                gate.withSlot {
                    holderInside.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        val queued = (1..bound).map { thread { runCatching { gate.withSlot { } } } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (gate.queued < bound && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(bound, gate.queued, "the first $bound callers must queue, not be shed")

        val startedAt = System.nanoTime()
        assertFailsWith<HeavyQueryBusyException> { gate.withSlot { } }
        val shedAfterMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(shedAfterMs < 1_000, "one-too-many caller was parked for $shedAfterMs ms instead of shed")

        release.countDown()
        holder.join(5_000)
        queued.forEach { it.join(10_000) }
        assertEquals(0, gate.queued)
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `a failing query gives its slot back`() {
        val gate = HeavyQueryGate(HeavyQuery.DISTRIBUTION, permits = 2, waitMs = 50)

        // More failures than permits: if a failure leaked its slot the gate would now be shut.
        repeat(5) {
            assertFailsWith<IllegalStateException> { gate.withSlot { error("query failed") } }
        }

        assertEquals(0, gate.inFlight, "a failed query kept its slot")
        assertEquals("ok", gate.withSlot { "ok" })
    }

    @Test
    fun `statement and transaction timeouts are recognised anywhere in the cause chain`() {
        val statementTimeout = SQLException("ERROR: canceling statement due to statement timeout", "57014")
        assertTrue(HeavyQueryGuard.isQueryTimeout(JpaSystemException(RuntimeException(statementTimeout))))
        assertTrue(HeavyQueryGuard.isQueryTimeout(jakarta.persistence.QueryTimeoutException("timeout")))
        assertTrue(HeavyQueryGuard.isQueryTimeout(TransactionTimedOutException("deadline reached")))
        assertTrue(HeavyQueryGuard.isQueryTimeout(jakarta.persistence.PersistenceException(org.hibernate.TransactionException("transaction timeout expired"))))

        // Anything else stays what it was (a real bug must not hide behind a 503).
        assertFalse(HeavyQueryGuard.isQueryTimeout(SQLException("relation does not exist", "42P01")))
        assertFalse(HeavyQueryGuard.isQueryTimeout(IllegalStateException("boom")))
    }

    @Test
    fun `a shed request is a 503 with Retry-After and the search-busy code`() {
        val response = ApiErrorHandler().handleHeavyQueryBusyException(HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.SATURATED))

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("5", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals(ApiErrorCodes.SEARCH_BUSY.code, response.body!!.code)
    }
}
