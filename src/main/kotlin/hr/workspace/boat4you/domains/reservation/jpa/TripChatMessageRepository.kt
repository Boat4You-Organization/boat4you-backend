package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TripChatMessageRepository : JpaRepository<TripChatMessage, Long> {
    fun findTop200ByReservationIdAndIdGreaterThanOrderByIdAsc(reservationId: Long, sinceId: Long): List<TripChatMessage>

    fun findTop200ByReservationIdOrderByIdDesc(reservationId: Long): List<TripChatMessage>

    fun existsByReservationIdAndAutomationTag(reservationId: Long, automationTag: String): Boolean

}
