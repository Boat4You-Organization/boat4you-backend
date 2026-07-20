package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import org.openapitools.client.mmk.model.Flexibility
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Heals stale UNAVAILABLE offer weeks against MMK per-yacht truth.
 *
 * Why: the yearly agency-level sweep (flexibility=6) is NOT a complete picture — proven live
 * 20.7.2026 on Shamane/6777 (56 offers for a whole year, zero for June 2027, while the exact-date
 * per-yacht call returned those weeks FREE at 5,580 €). The sweep's withdrawal pass therefore
 * ground ~81k bookable weeks into UNAVAILABLE (lost sales). The withdrawal is removed; this
 * service is the only authority allowed to touch such weeks, using the one MMK call shape that
 * is reliable: flexibility=1 with the row's exact dates and the single yachtId.
 *
 * A returned offer flips the row to its true status/price via the regular upsert
 * ([MmkYachtOfferSyncService.syncOffers]); an empty response means the partner genuinely does
 * not sell that week — the row stays UNAVAILABLE, which is correct.
 */
@Service
class MmkStaleOfferReverifyService(
    private val offerRepository: OfferRepository,
    private val mmkRetryableClient: MmkRetryableClient,
    private val mmkYachtOfferSyncService: MmkYachtOfferSyncService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        // Measured 20.7.2026: MMK answers these small exact-date calls in ~1-2s, so the
        // worker cycle is latency-dominated — 8 workers with light pacing lands at ~4-5
        // calls/s fleet-wide (52k backlog ≈ 3h), still far below what the nightly sweep
        // itself throws at MMK.
        private const val WORKER_COUNT = 8
        private const val CALL_PACING_MS = 150L
        // A yacht whose calls keep failing (e.g. "Illegal access" agency) is abandoned after
        // this many consecutive errors so one broken agency can't stall the whole run.
        private const val MAX_CONSECUTIVE_ERRORS_PER_YACHT = 3
        private const val PROGRESS_LOG_EVERY = 500
    }

    @Volatile
    private var running = false

    /**
     * Reverifies every stale-UNAVAILABLE combo. Safe to call repeatedly (skips if a run is
     * already in progress). Returns the number of combos MMK confirmed as still sellable.
     */
    fun reverifyStaleUnavailableOffers(): Int {
        if (running) {
            log.info("MMK stale-offer reverify already running — skipping this trigger")
            return 0
        }
        running = true
        try {
            return doReverify()
        } finally {
            running = false
        }
    }

    private fun doReverify(): Int {
        val combos =
            offerRepository.findStaleUnavailableMmkCombos(ExternalSystemEnum.MMK.value.toLong())
        log.info("MMK stale-offer reverify: ${combos.size} yacht-week combos on ${combos.map { it.yachtId }.distinct().size} yachts")
        if (combos.isEmpty()) return 0

        val yachtQueue = ConcurrentLinkedQueue(combos.groupBy { it.yachtId }.values)
        val processed = AtomicInteger()
        val reactivated = AtomicInteger()
        val failed = AtomicInteger()
        val latch = CountDownLatch(WORKER_COUNT)

        repeat(WORKER_COUNT) { workerIdx ->
            Thread({
                try {
                    while (true) {
                        val yachtWeeks = yachtQueue.poll() ?: break
                        reverifyYacht(yachtWeeks, processed, reactivated, failed, combos.size)
                    }
                } finally {
                    latch.countDown()
                }
            }, "mmk-stale-reverify-$workerIdx").start()
        }
        latch.await()

        log.info(
            "MMK stale-offer reverify done: ${processed.get()} combos checked, " +
                "${reactivated.get()} confirmed sellable by MMK (healed), ${failed.get()} call failures",
        )
        return reactivated.get()
    }

    private fun reverifyYacht(
        yachtWeeks: List<StaleMmkOfferCombo>,
        processed: AtomicInteger,
        reactivated: AtomicInteger,
        failed: AtomicInteger,
        totalCombos: Int,
    ) {
        var consecutiveErrors = 0
        for (combo in yachtWeeks) {
            try {
                val response =
                    mmkRetryableClient.getOffers(
                        dateFrom =
                            MmkDateTimeWrapper(
                                LocalDateTime.of(combo.dateFrom, LocalTime.MIN)
                                    .format(MmkDateTimeWrapper.READ_FORMATTER),
                            ),
                        dateTo =
                            MmkDateTimeWrapper(
                                LocalDateTime.of(combo.dateTo, LocalTime.MIN)
                                    .format(MmkDateTimeWrapper.READ_FORMATTER),
                            ),
                        flexibility = Flexibility._1,
                        yachtId = listOf(combo.externalYachtId),
                        showOptions = true,
                    )
                consecutiveErrors = 0
                if (response.isNotEmpty()) {
                    mmkYachtOfferSyncService.syncOffers(response)
                    reactivated.incrementAndGet()
                }
            } catch (e: Exception) {
                failed.incrementAndGet()
                consecutiveErrors++
                log.warn(
                    "MMK stale-offer reverify failed for yacht=${combo.yachtId} " +
                        "week=${combo.dateFrom}..${combo.dateTo}: ${e.message}",
                )
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS_PER_YACHT) {
                    log.warn(
                        "MMK stale-offer reverify: abandoning yacht=${combo.yachtId} after " +
                            "$consecutiveErrors consecutive failures (agency access issue?)",
                    )
                    return
                }
            } finally {
                val done = processed.incrementAndGet()
                if (done % PROGRESS_LOG_EVERY == 0) {
                    log.info("MMK stale-offer reverify progress: $done/$totalCombos combos")
                }
            }
            Thread.sleep(CALL_PACING_MS)
        }
    }
}
