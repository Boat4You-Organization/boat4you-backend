package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class ReservationOfferService(
    private val externalReservationRepository: ExternalReservationRepository,
    private val offerRepository: OfferRepository,
) {
    @Transactional
    fun deleteExpiredReservationsAndOffers() {
        offerRepository.deleteExpiredOffers()
        externalReservationRepository.deleteExpiredReservations(LocalDate.now().minusDays(30))
    }
}
