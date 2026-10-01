package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException.Reason
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The public reads that scan the whole `yacht_search_view` and are therefore gated. */
enum class HeavyQuery {
    /** `GET /public/yachts/distribution` — 9-11 aggregate scans per call (YachtDistributionService). */
    DISTRIBUTION,

    /** `GET /public/yachts` — the search listing page + its count (YachtQueryingService.getYachts). */
    SEARCH_LIST,
}

/**
 * Runs a heavy read: bounded concurrency first, then its own read-only transaction with a
 * statement timeout. An interface so the Testcontainers search tests can construct the services
 * without a transaction manager (they pass a runner that just calls the block).
 */
interface HeavyQueryRunner {
    fun <T> read(
        query: HeavyQuery,
        block: () -> T,
    ): T
}

/**
 * Fixed-size, fair gate with a bounded queue — the same shape as the image resize gate in
 * [YachtImageService] (16.9.2026 cusma2 load incident). A caller that cannot get a slot within
 * `waitMs`, or arrives while more than `permits * MAX_WAITERS_PER_PERMIT` callers are already
 * parked, is refused with [HeavyQueryBusyException] instead of queueing on a Tomcat worker.
 */
class HeavyQueryGate(
    val query: HeavyQuery,
    permits: Int,
    private val waitMs: Long,
) {
    val permits: Int = permits.coerceAtLeast(1)
    private val slots = Semaphore(this.permits, true)
    private val maxWaiters = this.permits * MAX_WAITERS_PER_PERMIT
    private val waiting = AtomicInteger()

    fun <T> withSlot(block: () -> T): T {
        if (!tryAcquire()) throw HeavyQueryBusyException(query, Reason.SATURATED)
        try {
            return block()
        } finally {
            // finally, not the happy path only: a failing or timed-out query must give its slot
            // back, or the gate would close for good after `permits` failures.
            slots.release()
        }
    }

    private fun tryAcquire(): Boolean {
        if (waiting.incrementAndGet() > maxWaiters) {
            waiting.decrementAndGet()
            return false
        }
        return try {
            slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** Threads parked waiting for a slot right now — test seam for the queue bound. */
    internal val queued: Int
        get() = waiting.get()

    /** Slots held right now — test seam (0 once every caller has left, whatever way). */
    internal val inFlight: Int
        get() = permits - slots.availablePermits()

    companion object {
        /** Queue depth per permit: absorbs a short burst, stays a small share of the 200 Tomcat workers. */
        internal const val MAX_WAITERS_PER_PERMIT = 4
    }
}

/**
 * 1.10.2026 (Codex audit F2, verified): boat pages took 15-25 s and failed because the Hikari pool
 * (35) ran empty 100-400 times an hour in 1-3 minute bursts — `waiting=168`, and 22 connections were
 * held > 60 s, every one of them by `YachtDistributionController.getDistribution`. A burst of cold
 * landing pages (distribution + listing per page) took every connection, so the cheap detail and
 * standard-offers reads waited 20 s for one and failed. Three bounds, in this order:
 *
 * 1. **Gate** per heavy query (before any connection is taken): at most `max-concurrent` run at
 *    once; the rest are shed with a fast 503 + Retry-After. Distribution 4 + listing 6 = at most
 *    10 of the 35 connections, so detail, offers and booking always find one. The DB has 2 cores,
 *    so more parallel scans would not finish sooner anyway.
 * 2. **Own read-only transaction** (TransactionTemplate) — the callers run without one
 *    (getDistribution has no @Transactional, getYachts is NOT_SUPPORTED), so a cache hit or a shed
 *    request never touches the pool, and waiting for a slot never holds a connection.
 * 3. **Timeouts**: `SET LOCAL statement_timeout` (server side, per statement, reset at
 *    commit/rollback so the pooled connection goes back clean) and the transaction timeout
 *    (Hibernate gives each statement only the time left, so all 9-11 distribution scans together
 *    are bounded too). A query abandoned by the web (b4y gives up on distribution after 1.5 s)
 *    then frees its connection in seconds, not minutes; a timeout is shed as 503 as well.
 */
@Component
class HeavyQueryGuard(
    transactionManager: PlatformTransactionManager,
    private val entityManager: EntityManager,
    @Value("\${application.heavy-queries.distribution.max-concurrent}")
    distributionPermits: Int,
    @Value("\${application.heavy-queries.search-list.max-concurrent}")
    searchListPermits: Int,
    @Value("\${application.heavy-queries.wait-ms}")
    waitMs: Long,
    @Value("\${application.heavy-queries.statement-timeout-ms}")
    private val statementTimeoutMs: Long,
    @Value("\${application.heavy-queries.transaction-timeout-seconds}")
    transactionTimeoutSeconds: Int,
) : HeavyQueryRunner {
    private val log = LoggerFactory.getLogger(this::class.java)

    internal val gates: Map<HeavyQuery, HeavyQueryGate> =
        mapOf(
            HeavyQuery.DISTRIBUTION to HeavyQueryGate(HeavyQuery.DISTRIBUTION, distributionPermits, waitMs),
            HeavyQuery.SEARCH_LIST to HeavyQueryGate(HeavyQuery.SEARCH_LIST, searchListPermits, waitMs),
        )

    private val readTx =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            timeout = transactionTimeoutSeconds
        }

    private val saturated = AtomicLong()
    private val timedOut = AtomicLong()
    private val lastWarnAtMs = AtomicLong()

    override fun <T> read(
        query: HeavyQuery,
        block: () -> T,
    ): T {
        try {
            return gates.getValue(query).withSlot {
                @Suppress("UNCHECKED_CAST")
                readTx.execute { status ->
                    // Only on a transaction we started: SET LOCAL inside a caller's transaction
                    // would stay in force for the rest of the caller's work.
                    if (status.isNewTransaction) applyStatementTimeout()
                    block()
                } as T
            }
        } catch (e: HeavyQueryBusyException) {
            logShed(e.query, e.reason)
            throw e
        } catch (e: RuntimeException) {
            if (!isQueryTimeout(e)) throw e
            logShed(query, Reason.TIMED_OUT)
            throw HeavyQueryBusyException(query, Reason.TIMED_OUT, e)
        }
    }

    // set_config(…, true) == SET LOCAL, but as a SELECT: a native executeUpdate would make
    // Hibernate treat it as a bulk write. Not a write — allowed in the read-only transaction.
    private fun applyStatementTimeout() {
        entityManager
            .createNativeQuery("SELECT set_config('statement_timeout', :timeout, true)")
            .setParameter("timeout", "${statementTimeoutMs}ms")
            .singleResult
    }

    /**
     * Saturation comes in bursts, so count every shed request but WARN at most once a minute with
     * the running totals (same throttle as the image gate — one line per request was the flood).
     */
    private fun logShed(
        query: HeavyQuery,
        reason: Reason,
    ) {
        if (reason == Reason.SATURATED) saturated.incrementAndGet() else timedOut.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastWarnAtMs.get()
        if (now - last >= SHED_WARN_INTERVAL_MS && lastWarnAtMs.compareAndSet(last, now)) {
            log.warn(
                "Heavy query shed with 503 ({} {}); gates {}; since start {} saturated, {} timed out (statement {} ms); " +
                    "next warning in >= 1 min",
                query,
                reason,
                gates.values.joinToString { "${it.query}=${it.inFlight}/${it.permits} running, ${it.queued} queued" },
                saturated.get(),
                timedOut.get(),
                statementTimeoutMs,
            )
        }
    }

    companion object {
        private const val SHED_WARN_INTERVAL_MS = 60_000L
        private const val MAX_CAUSE_DEPTH = 12

        /** SQLSTATE query_canceled: statement_timeout, or the JDBC cancel behind the transaction timeout. */
        private const val QUERY_CANCELED = "57014"

        internal fun isQueryTimeout(e: Throwable): Boolean =
            generateSequence(e) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .any {
                    it is TransactionTimedOutException ||
                        it is jakarta.persistence.QueryTimeoutException ||
                        it is org.hibernate.QueryTimeoutException ||
                        it is org.springframework.dao.QueryTimeoutException ||
                        (it is SQLException && it.sqlState == QUERY_CANCELED) ||
                        // Hibernate's own deadline check before the next statement.
                        (it is org.hibernate.TransactionException && it.message?.contains("timeout", ignoreCase = true) == true)
                }
    }
}
