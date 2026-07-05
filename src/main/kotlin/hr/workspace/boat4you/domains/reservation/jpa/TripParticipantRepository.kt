package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TripParticipantRepository : JpaRepository<TripParticipant, Long> {
    fun findByParticipantKey(participantKey: String): TripParticipant?

    fun findAllByReservationIdOrderByCreatedAtAsc(reservationId: Long): List<TripParticipant>

    fun countByReservationIdAndRemovedFalse(reservationId: Long): Long

    fun findFirstByReservationIdAndRoleAndRemovedFalse(
        reservationId: Long,
        role: TripParticipantRole,
    ): TripParticipant?
}
