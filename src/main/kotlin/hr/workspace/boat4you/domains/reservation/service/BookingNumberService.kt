package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.jpa.BookingSequence
import hr.workspace.boat4you.domains.reservation.jpa.BookingSequenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Hands out the next customer-facing booking number for a given charter year.
 *
 * Format: `"1001" + sequence + "/" + charterYear` (e.g. `100176/2026`).
 * Prefix `1001` is fixed; sequence restarts at 1 for each new charter year
 * and is unpadded — so 99 becomes `100199/…` and the next one is
 * `1001100/…`.
 *
 * Charter year is the year the charter starts, not the year the reservation
 * was booked — a reservation booked in December 2025 for a charter that
 * begins 15 May 2026 gets a `…/2026` number.
 */
@Service
class BookingNumberService(
    private val bookingSequenceRepository: BookingSequenceRepository,
) {
    private companion object {
        const val PREFIX = "1001"
    }

    /**
     * Reserves the next number for the given charter year and returns the
     * formatted string. Uses a pessimistic write lock on the year's counter
     * row so concurrent callers never collide on the same sequence.
     *
     * Runs in its own REQUIRES_NEW transaction so the counter increment
     * commits independently of the outer reservation transaction — if the
     * outer reservation later rolls back we'll skip a number, which is
     * acceptable and much simpler than trying to reclaim it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun next(charterYear: Int): String {
        val row =
            bookingSequenceRepository.findByCharterYearForUpdate(charterYear)
                ?: BookingSequence().apply {
                    this.charterYear = charterYear
                    this.lastSequence = 0
                }

        row.lastSequence += 1
        bookingSequenceRepository.save(row)

        return "$PREFIX${row.lastSequence}/$charterYear"
    }
}
