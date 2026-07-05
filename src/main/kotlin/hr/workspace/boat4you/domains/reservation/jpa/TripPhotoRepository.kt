package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface TripPhotoRepository : JpaRepository<TripPhoto, Long> {
    fun findAllByReservationIdOrderByIdDesc(reservationId: Long): List<TripPhoto>

    fun countByReservationId(reservationId: Long): Long

    /** Photos whose charter ended before [cutoff] — the 10-day GDPR retention
     *  purge target (rows + NFS files). */
    @Query(
        """
        SELECT p FROM TripPhoto p
        WHERE p.reservationId IN (
            SELECT r.id FROM Reservation r WHERE r.dateTo IS NOT NULL AND r.dateTo < :cutoff
        )
    """,
    )
    fun findExpired(@Param("cutoff") cutoff: LocalDateTime): List<TripPhoto>
}
