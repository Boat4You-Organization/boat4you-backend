package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        private const val RESERVATION_TYPE = "ExternalReservation"
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
     * Mirror the partner for one (agency, year): remove our reservations/options for the
     * agency's yachts that are dated in [year] but whose partner id is NOT in [seenExternalIds].
     * NauSys Occupancy / MMK Availability are documented COMPLETE per (company, year), so
     * "absent from a non-empty response = removed at the partner". An EMPTY response is treated
     * as no-data and deletes nothing (an API hiccup must never wipe valid reservations). Must run
     * only after a SUCCESSFUL fetch — the integration loop's per-(agency,year) try/catch ensures
     * a failed call never reaches here.
     */
    @Transactional
    fun reconcileAbsent(
        externalSystem: ExternalSystem,
        agencyYachts: List<Yacht>,
        seenExternalIds: Set<Long>,
        year: Int,
    ) {
        if (seenExternalIds.isEmpty()) {
            log.warn("Skip absent-reconcile (year=$year): partner returned ZERO reservations — treated as no-data, not all-free")
            return
        }
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year + 1, 1, 1)

        // First pass: collect in-scope rows + the subset the partner no longer returns.
        // Only rows whose period overlaps this synced year are in scope — a row in another year
        // isn't covered by this year's response (a later year's reconcile owns it).
        var inScopeCount = 0
        val toRemove = mutableListOf<Pair<ExternalReservation, ExternalMapping>>()
        agencyYachts.forEach { yacht ->
            if (yacht.id == null) return@forEach
            val inYear =
                externalReservationRepository.findAllByYacht(yacht).filter { r ->
                    val from = r.dateFrom ?: return@filter false
                    val to = r.dateTo ?: from
                    from < yearEnd && to > yearStart
                }
            inScopeCount += inYear.size
            inYear.forEach { res ->
                val resId = res.id ?: return@forEach
                val mapping = externalMappingRepository.findBySystemIdAndType(resId, RESERVATION_TYPE)
                val extId = mapping?.externalId
                // Conservative: only a row we can confidently attribute to THIS partner response
                // (has a mapping with a partner id) that the partner no longer returns. Mapping-less
                // rows are left untouched.
                if (mapping != null && extId != null && extId !in seenExternalIds) {
                    toRemove.add(res to mapping)
                }
            }
        }
        if (toRemove.isEmpty()) return

        // CIRCUIT BREAKER (required pre-deploy review fix): a real cancellation wave is tiny
        // relative to total occupancy; a LARGE fraction looking "absent" almost always means the
        // partner returned a TRUNCATED-but-parseable (HTTP 200) response, not real removals — and
        // the empty-guard above only catches a fully-empty body. Refuse to mass-delete valid
        // reservations: skip the whole reconcile, log, and self-heal on the next complete response.
        val maxDeletable = PartnerWithdrawalGuard.maxWithdrawable(inScopeCount)
        if (toRemove.size > maxDeletable) {
            log.warn(
                "Skip absent-reconcile (year=$year): would delete ${toRemove.size} of $inScopeCount in-scope " +
                    "external_reservations (over cap $maxDeletable) — likely a partial/truncated partner response, " +
                    "not real cancellations. Deleting nothing; will retry on the next complete response.",
            )
            return
        }

        toRemove.forEach { (res, mapping) -> removeReservationCascade(res, mapping) }
        log.info(
            "Absent-reconcile year=$year: removed ${toRemove.size} of $inScopeCount external_reservations " +
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
