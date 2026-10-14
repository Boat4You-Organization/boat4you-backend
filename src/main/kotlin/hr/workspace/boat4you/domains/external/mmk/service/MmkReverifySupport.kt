package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.OfferStatusPrice
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyCounters
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.openapitools.client.mmk.model.Offer as MmkOffer

private val supportLog = LoggerFactory.getLogger("hr.workspace.boat4you.domains.external.mmk.service.MmkReverifySupport")

/**
 * Live counters of one `reverify_run` row. Worker threads bump the atomics; [flush] (heartbeat) and [finish] write
 * only the delta since the last write, so a crash mid-run still leaves the partial numbers in the table.
 * Bookkeeping must never break a run: a failed write is logged and retried with the next delta.
 */
internal class RunTally(
    private val store: ReverifyRunStore,
    val runId: Long,
) {
    val calls = AtomicInteger()
    val quoted = AtomicInteger()
    val empty = AtomicInteger()
    val unknown = AtomicInteger()
    val upserts = AtomicInteger()
    val firstStrikes = AtomicInteger()
    val hiddenPeriods = AtomicInteger()
    val hiddenRows = AtomicInteger()
    val wouldFlipRows = AtomicInteger()

    private var written = ReverifyCounters()

    fun snapshot() =
        ReverifyCounters(
            calls = calls.get(),
            quoted = quoted.get(),
            empty = empty.get(),
            unknown = unknown.get(),
            upserts = upserts.get(),
            firstStrikes = firstStrikes.get(),
            hiddenPeriods = hiddenPeriods.get(),
            hiddenRows = hiddenRows.get(),
            wouldFlipRows = wouldFlipRows.get(),
        )

    @Synchronized
    fun flush() {
        val now = snapshot()
        val delta = now.minus(written)
        if (delta == ReverifyCounters()) return
        runCatching { store.addCounters(runId, delta) }
            .onSuccess { written = now }
            .onFailure { supportLog.warn("reverify_run {}: counter flush failed: {}", runId, it.message) }
    }

    @Synchronized
    fun finish(
        abortedReason: String?,
        escalatedAgencyIds: Collection<Long>?,
    ) {
        val now = snapshot()
        runCatching { store.finish(runId, now.minus(written), abortedReason, escalatedAgencyIds) }
            .onSuccess { written = now }
            .onFailure { supportLog.warn("reverify_run {}: finish failed: {}", runId, it.message) }
    }

    fun line(): String {
        val c = snapshot()
        return "calls=${c.calls} quoted=${c.quoted} empty=${c.empty} unknown=${c.unknown} upserts=${c.upserts} " +
            "firstStrikes=${c.firstStrikes} hidden=${c.hiddenPeriods}/${c.hiddenRows} rows wouldFlip=${c.wouldFlipRows}"
    }

    private fun ReverifyCounters.minus(o: ReverifyCounters) =
        ReverifyCounters(
            calls - o.calls,
            quoted - o.quoted,
            empty - o.empty,
            unknown - o.unknown,
            upserts - o.upserts,
            firstStrikes - o.firstStrikes,
            hiddenPeriods - o.hiddenPeriods,
            hiddenRows - o.hiddenRows,
            wouldFlipRows - o.wouldFlipRows,
        )
}

/**
 * Runs [beat] every [every] on a daemon thread until closed: long single-purpose jobs log an INFO line at least that
 * often, so the cusma3 restart gate (journal grep) sees a quiet run that is still working (DESIGN.md section 1.4).
 */
internal class Heartbeat(
    every: Duration,
    name: String,
    beat: () -> Unit,
) : AutoCloseable {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    init {
        val ms = every.toMillis().coerceAtLeast(1)
        executor.scheduleAtFixedRate(
            { runCatching(beat).onFailure { supportLog.warn("{} failed: {}", name, it.message) } },
            ms,
            ms,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * Collects `partner_period_check` bookkeeping from the worker threads and writes it in JDBC batches of [batchSize]
 * (and on [flush]). A failed batch is logged and dropped: bookkeeping never stops a run, and the next run re-records.
 */
internal class PeriodCheckBuffer(
    private val store: PartnerPeriodCheckStore,
    private val today: LocalDate,
    private val batchSize: Int = DEFAULT_BATCH,
    /** The partner the rows belong to (the NauSys reverifier shares this buffer). */
    private val sys: ExternalSystemEnum = ExternalSystemEnum.MMK,
) {
    private val pending = ArrayList<PeriodCheck>()

    fun add(check: PeriodCheck) {
        val batch =
            synchronized(this) {
                pending += check
                if (pending.size >= batchSize) drain() else null
            }
        batch?.let(::write)
    }

    fun flush() {
        write(synchronized(this) { drain() })
    }

    private fun drain(): List<PeriodCheck> = ArrayList(pending).also { pending.clear() }

    private fun write(batch: List<PeriodCheck>) {
        if (batch.isEmpty()) return
        runCatching { store.recordCheckedBatch(sys, batch, today) }
            .onFailure { supportLog.warn("{} period-check bookkeeping: {} rows not written: {}", sys, batch.size, it.message) }
    }

    private companion object {
        const val DEFAULT_BATCH = 500
    }
}

/** How an exact-date MMK answer reads as evidence for exactly one period (DESIGN.md WS-B core rule). */
internal object MmkQuotes {
    /** Above this relative difference between the quoted and the stored partner total the row is refreshed (path 8). */
    private val PRICE_DRIFT: BigDecimal = BigDecimal("0.01")

    private fun datesOf(offer: MmkOffer): Pair<LocalDate?, LocalDate?>? =
        // Mocked or partial partner rows may lack dates; such a row is simply not a row for these dates.
        runCatching { offer.dateFrom.value?.toLocalDate() to offer.dateTo.value?.toLocalDate() }.getOrNull()

    /** The rows of [response] for exactly [from]..[to]. */
    fun exactRows(
        response: List<MmkOffer>,
        from: LocalDate,
        to: LocalDate,
    ): List<MmkOffer> = response.filter { datesOf(it) == (from to to) }

    /** The bookkeeping result of a non-empty answer: FREE beats an option, an option beats anything else. */
    fun result(
        response: List<MmkOffer>,
        from: LocalDate,
        to: LocalDate,
    ): PeriodCheckResult {
        val statuses = exactRows(response, from, to).map { OfferStatus.fromMmkValue(it.status) }
        return when {
            OfferStatus.FREE in statuses -> PeriodCheckResult.QUOTED_FREE
            statuses.any { it == OfferStatus.OPTION || it == OfferStatus.OPTION_WAITING } -> PeriodCheckResult.QUOTED_OPTION
            else -> PeriodCheckResult.QUOTED_OTHER
        }
    }

    /**
     * Drift (DESIGN.md B1, path 8): some exact-date row MMK quotes has no stored row of the same period with the same
     * mapped status and a partner total (`ext_total_price` = `mmkOffer.price`) within 1 %. An answer without an
     * exact-date row says nothing about these dates and never drifts.
     */
    fun drifted(
        response: List<MmkOffer>,
        from: LocalDate,
        to: LocalDate,
        stored: List<OfferStatusPrice>,
    ): Boolean =
        exactRows(response, from, to).any { q ->
            val status = OfferStatus.fromMmkValue(q.status).name
            val price = runCatching { BigDecimal.valueOf(q.price) }.getOrNull()
            stored.none { s -> s.status == status && samePrice(price, s.extTotalPrice) }
        }

    private fun samePrice(
        quoted: BigDecimal?,
        stored: BigDecimal?,
    ): Boolean {
        if (quoted == null || stored == null) return quoted == stored
        return (quoted - stored).abs() <= stored.abs() * PRICE_DRIFT
    }
}
