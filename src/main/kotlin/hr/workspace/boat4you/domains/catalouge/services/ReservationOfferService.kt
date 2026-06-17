package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class ReservationOfferService(
    private val externalReservationRepository: ExternalReservationRepository,
    private val offerRepository: OfferRepository,
    private val externalAvailabilityReconcileService: ExternalAvailabilityReconcileService,
) {
    @Transactional
    fun deleteExpiredReservationsAndOffers() {
        offerRepository.deleteExpiredOffers()
        externalReservationRepository.deleteExpiredReservations(LocalDate.now().minusDays(30))
        // Drop options whose hold has lapsed at the partner (+ their synthetic OPTION offer /
        // mapping) so an expired option never keeps a boat badged "under option".
        externalAvailabilityReconcileService.purgeExpiredOptions()
    }
}
