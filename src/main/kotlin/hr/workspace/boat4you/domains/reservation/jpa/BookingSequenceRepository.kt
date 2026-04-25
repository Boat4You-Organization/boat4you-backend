package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BookingSequenceRepository : JpaRepository<BookingSequence, Int> {
    // Pessimistic write lock so concurrent reservations for the same charter
    // year don't hand out the same sequence number. Lock is held only for the
    // duration of the enclosing transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bs FROM BookingSequence bs WHERE bs.charterYear = :charterYear")
    fun findByCharterYearForUpdate(
        @Param("charterYear") charterYear: Int,
    ): BookingSequence?
}
