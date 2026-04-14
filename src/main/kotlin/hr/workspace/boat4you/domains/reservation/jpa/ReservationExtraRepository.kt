package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationExtraRepository : JpaRepository<ReservationExtra, Long> {
    @EntityGraph(attributePaths = ["extras"])
    fun findAllByReservationFlowId(reservationFlowId: Long): List<ReservationExtra>
}
