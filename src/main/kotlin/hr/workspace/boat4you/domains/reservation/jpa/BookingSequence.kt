package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Per-charter-year counter for the customer-facing booking number
 * ("1001{sequence}/{year}"). Each year is independent; a reservation made for
 * 2027 never touches the 2026 sequence.
 */
@Entity
@Table(name = "booking_sequence")
open class BookingSequence {
    @Id
    @Column(name = "charter_year", nullable = false)
    open var charterYear: Int = 0

    @Column(name = "last_sequence", nullable = false)
    open var lastSequence: Int = 0
}
