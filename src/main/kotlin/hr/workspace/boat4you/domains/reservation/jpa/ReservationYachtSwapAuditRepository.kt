package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface ReservationYachtSwapAuditRepository : JpaRepository<ReservationYachtSwapAudit, Long> {
    fun findAllByReservationIdOrderByDetectedAtDesc(reservationId: Long): List<ReservationYachtSwapAudit>

    fun findFirstByReservationIdAndAcknowledgedAtIsNullOrderByDetectedAtDesc(reservationId: Long): ReservationYachtSwapAudit?
}
