package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.ReservationNaturalKey
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.openapitools.client.mmk.model.AvailabilityResponse
import org.springframework.stereotype.Service

/**
 * Orchestrates the MMK availability sync for one (agency, year).
 *
 * This method is intentionally NOT @Transactional: each reservation is upserted in its own
 * short transaction via [MmkReservationUpsertService], and the removal pass keeps its own
 * transaction inside [ExternalAvailabilityReconcileService.reconcileAbsent]. Previously the
 * whole agency-year ran in one transaction; for a big agency that held a DB connection (and
 * table read-locks) for many minutes — a 54-min hold once blocked a deploy's ALTER TABLE and
 * took the site down. Per-reservation commits remove that. Mario rule 29.6.2026.
 *
 * Behavioural change to note: atomicity drops from per-(agency,year) to per-reservation — a
 * mid-run failure leaves earlier reservations committed. This is safe for a self-healing
 * mirror (the next sync re-derives), and `reconcileAbsent` still runs only if the loop
 * completes without throwing, so a partial loop never triggers mass-removal.
 */
@Service
class MmkAvailabilitySyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingService: ExternalMappingService,
    private val yachtRepository: YachtRepository,
    private val externalAvailabilityReconcileService: ExternalAvailabilityReconcileService,
    private val mmkReservationUpsertService: MmkReservationUpsertService,
) {
    companion object {
        /** Mirrors Nausys's marker so the offer sync cleanup can recognize
         * synthetic OPTION rows created from MMK availability and leave them alone. */
        const val SYNTHETIC_OPTION_EXT_STATUS = "SYNTHETIC_OPTION"
    }

    fun syncYachtAvailability(
        agencyId: Long,
        mmkResponse: List<AvailabilityResponse>,
        year: Int,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val yachtMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val agencyYachts = yachtRepository.findAllByAgencyId(agencyId)

        // Build the partner's "seen" set as NATURAL KEYS (our yacht id + dates + status) from the
        // SAME conversion the upsert writes with, so reconcileAbsent mirrors removals WITHOUT relying
        // on external-id mappings (which go missing / duplicate). seenYachtIds gates which yachts the
        // reconcile may touch (per-yacht-present guard).
        val seenKeys = mutableSetOf<ReservationNaturalKey>()
        val seenYachtIds = mutableSetOf<Long>()
        mmkResponse.forEach { mmkReservation ->
            val yacht = getYacht(yachtMappings, agencyYachts, mmkReservation) ?: return@forEach
            val yachtId = yacht.id ?: return@forEach
            // SHORT transaction per reservation (separate bean → proxy applies).
            mmkReservationUpsertService.upsertReservation(externalSystem, yachtId, mmkReservation)
            seenYachtIds.add(yachtId)
            ReservationNaturalKey.of(
                yachtId,
                mmkReservation.dateFrom?.value?.toLocalDate(),
                mmkReservation.dateTo?.value?.toLocalDate(),
                ExternalReservationStatus.fromMmkValue(mmkReservation.status),
            )?.let { seenKeys.add(it) }
        }

        // Mirror MMK: /availability is the COMPLETE set per (agency, year), so any reservation we
        // hold for this agency's yachts that MMK no longer returns (by natural key) was cancelled —
        // drop it. Empty key set = no-data → deletes nothing. Runs only after the upsert loop.
        externalAvailabilityReconcileService.reconcileAbsent(agencyYachts, seenKeys, seenYachtIds, year)
    }

    private fun getYacht(
        yachtMappings: List<ExternalMapping>,
        agencyYachts: List<Yacht>,
        mmkReservation: AvailabilityResponse,
    ): Yacht? {
        val yachtMapping = yachtMappings.find { mapping -> mapping.externalId == mmkReservation.yachtId!! }
        return agencyYachts.find { yacht -> yacht.id == yachtMapping?.systemId }
    }
}
