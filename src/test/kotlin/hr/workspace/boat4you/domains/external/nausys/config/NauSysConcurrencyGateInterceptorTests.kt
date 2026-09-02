package hr.workspace.boat4you.domains.external.nausys.config

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NauSysConcurrencyGateInterceptorTests {
    private val base = "http://ws.nausys.com/CBMS-external/rest"
    private val freeYachts = MockClientHttpRequest(HttpMethod.POST, URI.create("$base/yachtReservation/v6/freeYachts"))
    private val occupancy = MockClientHttpRequest(HttpMethod.GET, URI.create("$base/yachtReservation/v6/occupancy/1277/2026"))
    private val allYachts = MockClientHttpRequest(HttpMethod.POST, URI.create("$base/catalogue/v6/yachts/1277"))
    private val createOption = MockClientHttpRequest(HttpMethod.POST, URI.create("$base/booking/v6/createOption"))

    private fun ok() = MockClientHttpResponse(ByteArray(0), HttpStatusCode.valueOf(200))

    @Test
    fun `second gated call waits for the first to release the single permit`() {
        val stats = NauSysRateLimitStats()
        val gate = NauSysConcurrencyGateInterceptor(1, 5_000, stats)
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicInteger()
        val blocking = ClientHttpRequestExecution { _, _ ->
            executed.incrementAndGet()
            firstEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            ok()
        }
        val quick = ClientHttpRequestExecution { _, _ ->
            executed.incrementAndGet()
            ok()
        }
        val t1 = Thread { gate.intercept(freeYachts, ByteArray(0), blocking) }
        t1.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val t2Done = CountDownLatch(1)
        val t2 = Thread {
            gate.intercept(occupancy, ByteArray(0), quick)
            t2Done.countDown()
        }
        t2.start()
        Thread.sleep(200)
        assertEquals(1, executed.get(), "second gated call must not execute while the permit is held")
        release.countDown()
        assertTrue(t2Done.await(5, TimeUnit.SECONDS))
        t1.join(5_000)
        assertEquals(2, executed.get())
        assertEquals(0, stats.exhausted.get())
    }

    @Test
    fun `gate wait timeout surfaces as NauSysRateLimitedException with zero attempts`() {
        val stats = NauSysRateLimitStats()
        val gate = NauSysConcurrencyGateInterceptor(1, 100, stats)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            gate.intercept(allYachts, ByteArray(0)) { _, _ ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ok()
            }
        }
        holder.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ex = assertFailsWith<NauSysRateLimitedException> { gate.intercept(freeYachts, ByteArray(0)) { _, _ -> ok() } }
        assertEquals(0, ex.attempts)
        assertEquals(1, stats.exhausted.get())
        release.countDown()
        holder.join(5_000)
    }

    @Test
    fun `booking endpoints bypass the gate even when all permits are held`() {
        val gate = NauSysConcurrencyGateInterceptor(1, 100, NauSysRateLimitStats())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            gate.intercept(freeYachts, ByteArray(0)) { _, _ ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ok()
            }
        }
        holder.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val result = AtomicReference<Int>()
        gate.intercept(createOption, ByteArray(0)) { _, _ -> ok().also { result.set(200) } }
        assertEquals(200, result.get())
        release.countDown()
        holder.join(5_000)
    }
}
