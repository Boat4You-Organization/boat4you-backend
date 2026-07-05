package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import java.time.Instant

interface TripChatMessageRepository : JpaRepository<TripChatMessage, Long> {
    fun findTop200ByReservationIdAndIdGreaterThanOrderByIdAsc(reservationId: Long, sinceId: Long): List<TripChatMessage>

    fun existsByReservationIdAndAutomationTag(reservationId: Long, automationTag: String): Boolean

    /** Non-concierge traffic of the last day, for the admin digest email. */
    @Query(
        """
        SELECT m FROM TripChatMessage m
        WHERE m.createdAt >= :since AND m.senderRole <> 'CONCIERGE'
        ORDER BY m.reservationId ASC, m.id ASC
    """,
    )
    fun findForDigest(@Param("since") since: Instant): List<TripChatMessage>
}
