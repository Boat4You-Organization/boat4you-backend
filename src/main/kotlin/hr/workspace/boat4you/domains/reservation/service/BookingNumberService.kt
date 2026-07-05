package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.jpa.BookingSequence
import hr.workspace.boat4you.domains.reservation.jpa.BookingSequenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Hands out the next customer-facing booking number for a given charter year.
 *
 * Format: `"1441" + sequence(≥3 digits, zero-padded) + "/" + charterYear`
 * (e.g. `1441001/2026`, `1441002/2026`). Prefix `1441` is fixed; the sequence
 * restarts at 1 for each new charter year and is zero-padded to a minimum of
 * three digits — so 1 is `1441001/…`, 999 is `1441999/…` and 1000 grows to
 * `14411000/…`.
 *
 * Prefix changed from `1001` to `1441` on 5.7.2026 (Mario): the online bookings
 * this year must be numbered distinctly from the legacy back-office system so
 * the two documentation streams don't clash. V9_33 reset the per-year counters
 * so the first new-prefix number is `1441001/{year}`; existing `1001…` numbers
 * keep their old prefix (no collision — different prefix).
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
        const val PREFIX = "1441"
        const val MIN_SEQUENCE_DIGITS = 3
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

        val sequence = row.lastSequence.toString().padStart(MIN_SEQUENCE_DIGITS, '0')
        return "$PREFIX$sequence/$charterYear"
    }
}
