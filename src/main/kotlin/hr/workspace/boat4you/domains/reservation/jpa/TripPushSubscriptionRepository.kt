package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TripPushSubscriptionRepository : JpaRepository<TripPushSubscription, Long> {
    fun findByEndpoint(endpoint: String): TripPushSubscription?

    fun findAllByReservationId(reservationId: Long): List<TripPushSubscription>

    fun findAllByReservationIdAndIsOwnerTrue(reservationId: Long): List<TripPushSubscription>
}
