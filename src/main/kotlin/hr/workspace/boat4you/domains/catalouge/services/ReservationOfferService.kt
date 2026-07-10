package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import org.springframework.stereotype.Service

@Service
class ReservationOfferService(
    private val externalAvailabilityReconcileService: ExternalAvailabilityReconcileService,
) {
    /**
     * 06:00 cleanup — now ONLY the expired-option purge (status-based mirror work:
     * lapsed holds, unbacked synthetic OPTION offers, orphaned mappings).
     *
     * The expired-OFFER and expired-external_reservation deletes that used to run
     * here moved to RetentionReaperJob (03:40, batched). Reason (12.7.2026 incident):
     * all three steps shared ONE giant @Transactional that silently never committed —
     * identical "Purge mirror" orphan counts on consecutive nights (83,925 on both
     * 7.7. and 8.7.) proved every night's work rolled back, accumulating a
     * 26k-offer / 78k-reservation backlog since January. purgeExpiredOptions carries
     * its own @Transactional, so standalone it commits (or fails) on its own, and the
     * reaper's per-batch commits can never lose a whole night again.
     */
    fun deleteExpiredReservationsAndOffers() {
        externalAvailabilityReconcileService.purgeExpiredOptions()
    }
}
