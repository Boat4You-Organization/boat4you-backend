package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TripPhotoRepository : JpaRepository<TripPhoto, Long> {
    fun findAllByReservationIdOrderByIdDesc(reservationId: Long): List<TripPhoto>

    fun countByReservationId(reservationId: Long): Long
}
