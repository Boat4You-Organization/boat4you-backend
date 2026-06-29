package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Keeps `external_reservations` a faithful COPY of each partner's current occupancy
 * ("kako na njima, tako kod nas"). The availability sync upserts what the partner returns
 * but historically never removed what the partner DROPPED, so expired options and cancelled
 * holds piled up forever (a yacht had 30+ option rows, most long expired) and kept boats
 * badged "under option" after the option lapsed. This service is the removal half:
 *  - [purgeExpiredOptions]: drop OPTION rows whose hold has expired (unconditional, safe).
 *  - [reconcileAbsent]: drop rows the partner no longer returns in a SUCCESSFUL, NON-EMPTY
 *    occupancy response for an (agency, year) — i.e. cancelled options / removed reservations.
 *
 * Removing a row cascades: its synthetic OPTION offer (the phantom that kept the boat visible)
 * and its ExternalMapping go too. Real offers that an option flipped FREE->OPTION are left for
 * the offer sync to re-derive (it owns FREE). RESERVATION capture + hard-block are unchanged:
 * a real booking still hides the boat; an active option still shows as an option.
 */
@Service
class ExternalAvailabilityReconcileService(
    private val externalReservationRepository: ExternalReservationRepository,
    private val externalMappingRepository: ExternalMappingRepository,
    private val offerRepository: OfferRepository,
    // Catastrophe firewall: ships ON. While true, reconcileAbsent logs what it WOULD delete and
    // deletes nothing, so a key bug can't wipe valid bookings. Flip RECONCILE_SHADOW=false only
    // after the shadow log proves the candidates are all real cancellations. Mario 29.6.2026.
    @Value("\${reconcile.shadow-mode:true}") private val shadowMode: Boolean,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        private const val RESERVATION_TYPE = "ExternalReservation"

        /** Cap on per-(agency,year) [SHADOW] WOULD-delete detail lines, so the backlog drain doesn't
         * flood the log; the summary line always carries the full count. */
        private const val SHADOW_SAMPLE_LIMIT = 25
    }

    @Transactional
    fun purgeExpiredOptions() {
        // Set-based so the one-time ~120k expired-option backlog clears in a few statements instead
        // of ~600k row-by-row queries that would hammer cusma4. Order: (1) drop synthetic OPTION
        // offers no longer backed by a live option (the visible phantom that kept a boat badged
        // "under option" after its option lapsed), (2) drop the expired OPTION reservation rows,
        // (3) clean the now-orphaned reservation mappings.
        val syntheticRemoved = offerRepository.deleteUnbackedSyntheticOptionOffers()
        val optionsRemoved =
            externalReservationRepository.deleteExpiredOptions(ExternalReservationStatus.OPTION, LocalDateTime.now())
        // Zombie RESERVATION rows: an expired option hold that was stored under RESERVATION status
        // (legacy/partner-echo) carries a NON-NULL optionExpiration in the past while keeping a
        // future dateTo. The OPTION purge above misses it (wrong status) and deleteExpiredReservations
        // (dateTo < cutoff) misses it too (future dateTo) — so it hard-blocks a boat the partner has
        // already freed forever. Discovered 2026-06-25: 87k such rows hid FREE weeks on 630 yachts.
        // Same query/safety as the OPTION purge — a REAL reservation has optionExpiration = NULL and
        // `NULL < cutoff` is false, so only the mis-statused expired holds are removed.
        val staleReservationsRemoved =
            externalReservationRepository.deleteExpiredOptions(ExternalReservationStatus.RESERVATION, LocalDateTime.now())
        val mappingsRemoved = externalMappingRepository.deleteOrphanReservationMappings(RESERVATION_TYPE)
        if (syntheticRemoved > 0 || optionsRemoved > 0 || staleReservationsRemoved > 0 || mappingsRemoved > 0) {
            log.info(
                "Purge mirror: removed $optionsRemoved expired OPTION reservations, $staleReservationsRemoved " +
                    "stale RESERVATION rows with an expired option hold, $syntheticRemoved unbacked synthetic " +
                    "OPTION offers, $mappingsRemoved orphan reservation mappings",
            )
        }
    }

    /**
     * Mirror the partner for one (agency, year): remove our reservations for the agency's yachts
     * that the partner's COMPLETE occupancy response no longer contains, matched by NATURAL KEY
     * (our yacht id + dates + status) instead of by external-id mapping. This makes the cleanup
     * immune to the mapping damage that made stale rows immortal — missing mappings (96k legacy
     * RESERVATION rows) and one partner id duplicate-mapped to two yachts (the Vi La Ut case where
     * the stale 4736 twin survived because the id was still "seen" via yacht 4741).
     *
     * Guards (a wrongful mass-delete wipes real availability = double-booking risk):
     *  - EMPTY-GUARD: an empty key set = no-data → delete nothing.
     *  - PER-YACHT-PRESENT: only yachts present in THIS response are reconciled; a yacht absent
     *    from it (yacht-mapping drift) keeps ALL its rows.
     *  - START-YEAR OWNERSHIP: only rows that START in [year] are deletion candidates, so a
     *    multi-year SERVICE block (e.g. 2026-12-01→2098) is owned solely by its start year's pass.
     *  - 30% CIRCUIT BREAKER: a large absent fraction = a truncated HTTP-200 response → skip.
     *  - SHADOW MODE: while on, logs candidates and deletes nothing.
     * Both sides build the key through the SAME conversion the upsert writes with, so a just-synced
     * valid row is byte-identical to its response key and can never look "absent". Must run only
     * after a SUCCESSFUL fetch (the integration loop's per-(agency,year) try/catch ensures this).
     */
    @Transactional
    fun reconcileAbsent(
        agencyYachts: List<Yacht>,
        seenKeys: Set<ReservationNaturalKey>,
        seenYachtIds: Set<Long>,
        year: Int,
    ) {
        if (seenKeys.isEmpty()) {
            log.warn("Skip absent-reconcile (year=$year): partner returned ZERO reservations — treated as no-data, not all-free")
            return
        }
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year + 1, 1, 1)

        // PER-YACHT-PRESENT guard: a yacht missing from the response is "no data for this yacht" →
        // none of its rows may be deleted (prevents yacht-mapping drift wiping a yacht's bookings).
        val yachtIds = agencyYachts.mapNotNull { it.id }.filter { it in seenYachtIds }
        if (yachtIds.isEmpty()) return

        val inScope = externalReservationRepository.findAllByYachtIdsAndYearOverlap(yachtIds, yearStart, yearEnd)
        // START-YEAR OWNERSHIP: only rows that START in this year are candidates, so a later year's
        // pass (which legitimately omits a block that began earlier) can't delete a multi-year block.
        val candidates = inScope.filter { res -> res.dateFrom?.let { it >= yearStart } ?: false }
        val toRemove =
            candidates.filter { res ->
                val key = ReservationNaturalKey.of(res.yacht?.id, res.dateFrom, res.dateTo, res.status)
                // Conservative: delete only a row we can confidently key AND that the partner no
                // longer returns. An unkeyable (corrupt) row is left untouched.
                key != null && key !in seenKeys
            }
        if (toRemove.isEmpty()) return

        // CIRCUIT BREAKER: a real cancellation wave is tiny vs total occupancy; a LARGE absent
        // fraction almost always = a truncated-but-parseable response. Refuse to mass-delete.
        val maxDeletable = PartnerWithdrawalGuard.maxWithdrawable(inScope.size)
        if (toRemove.size > maxDeletable) {
            log.warn(
                "Skip absent-reconcile (year=$year): would delete ${toRemove.size} of ${inScope.size} in-scope " +
                    "external_reservations (over cap $maxDeletable) — likely a partial/truncated partner response, " +
                    "not real cancellations. Deleting nothing; will retry on the next complete response.",
            )
            return
        }

        if (shadowMode) {
            toRemove.take(SHADOW_SAMPLE_LIMIT).forEach { res ->
                log.warn(
                    "[SHADOW] reconcile WOULD delete external_reservation ${res.id} yacht=${res.yacht?.id} " +
                        "${res.dateFrom}->${res.dateTo} ${res.status} (absent from partner response)",
                )
            }
            log.info("[SHADOW] Absent-reconcile year=$year: WOULD remove ${toRemove.size} of ${inScope.size} (no deletions; shadow mode ON)")
            return
        }

        toRemove.forEach { removeReservationCascade(it) }
        log.info(
            "Absent-reconcile year=$year: removed ${toRemove.size} of ${inScope.size} external_reservations " +
                "no longer returned by the partner",
        )
    }

    /**
     * Remove one external_reservation the partner dropped + its mapping. We deliberately do NOT
     * delete the synthetic OPTION offer or revert real offers here: [purgeExpiredOptions] cleans
     * unbacked synthetic offers centrally with a reservation_flow-guarded set-based query (so the
     * row-by-row reconcile path carries no offer FK risk), and the offer sync re-derives FREE for
     * real offers a dropped option had flipped.
     */
    private fun removeReservationCascade(
        res: ExternalReservation,
        knownMapping: ExternalMapping? = null,
    ) {
        val mapping = knownMapping
            ?: res.id?.let { externalMappingRepository.findBySystemIdAndType(it, RESERVATION_TYPE) }
        if (mapping != null) externalMappingRepository.delete(mapping)
        externalReservationRepository.delete(res)
    }
}
