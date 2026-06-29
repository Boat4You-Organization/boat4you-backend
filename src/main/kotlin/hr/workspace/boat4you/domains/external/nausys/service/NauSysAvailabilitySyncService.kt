package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.openapitools.client.nausys.model.RestYachtReservationOccupancy
import org.openapitools.client.nausys.model.RestYachtReservationOccupancyList
import org.springframework.stereotype.Service

/**
 * Orchestrates the NauSYS availability sync for one (agency, year).
 *
 * NOT @Transactional: each reservation is upserted in its own short transaction via
 * [NauSysReservationUpsertService], and the removal pass keeps its own transaction inside
 * [ExternalAvailabilityReconcileService.reconcileAbsent]. Previously the whole agency-year ran
 * in one transaction; for a big agency that held a DB connection for many minutes — a 54-min
 * hold once blocked a deploy's ALTER TABLE. Per-reservation commits remove that. Mario 29.6.2026.
 *
 * Atomicity drops from per-(agency,year) to per-reservation — safe for a self-healing mirror;
 * reconcileAbsent still runs only if the loop completes without throwing.
 */
@Service
class NauSysAvailabilitySyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingService: ExternalMappingService,
    private val yachtRepository: YachtRepository,
    private val externalAvailabilityReconcileService: ExternalAvailabilityReconcileService,
    private val nauSysReservationUpsertService: NauSysReservationUpsertService,
) {
    companion object {
        /** Marks offer rows that were created by availability sync as a stand-in for an external
         * agent's option. Offer sync cleanup must skip rows with this marker, otherwise it would
         * flip them back to UNAVAILABLE on the next pass (NauSys doesn't return them via
         * getFreeYachts). Referenced externally by NauSysYachtOfferSyncService. */
        const val SYNTHETIC_OPTION_EXT_STATUS = "SYNTHETIC_OPTION"
    }

    fun syncYachtAvailability(
        agencyId: Long,
        nausysResponse: RestYachtReservationOccupancyList,
        year: Int,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val yachtMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val agencyYachts = yachtRepository.findAllByAgencyId(agencyId)

        nausysResponse.reservations?.forEach { nausysReservation ->
            val yacht = getYacht(yachtMappings, agencyYachts, nausysReservation) ?: return@forEach
            // SHORT transaction per reservation (separate bean → proxy applies).
            nauSysReservationUpsertService.upsertReservation(externalSystem, yacht.id!!, nausysReservation)
        }

        // Mirror NauSYS: Occupancy is the COMPLETE set per (agency, year), so any reservation/
        // option we hold for this agency's yachts in this year that NauSYS no longer returns was
        // cancelled/removed at the partner — drop it. Empty response = no-data → deletes nothing.
        // Runs only after the upsert loop completes without throwing. reconcileAbsent carries its
        // own @Transactional → its own (no longer combined) tx.
        val seenExternalIds = nausysResponse.reservations?.mapNotNull { it.id }?.toSet() ?: emptySet()
        externalAvailabilityReconcileService.reconcileAbsent(externalSystem, agencyYachts, seenExternalIds, year)
    }

    private fun getYacht(
        yachtMappings: List<ExternalMapping>,
        agencyYachts: List<Yacht>,
        nausysReservation: RestYachtReservationOccupancy,
    ): Yacht? {
        val yachtMapping = yachtMappings.find { mapping -> mapping.externalId == nausysReservation.yachtId!! }
        return agencyYachts.find { yacht -> yacht.id == yachtMapping?.systemId }
    }
}
