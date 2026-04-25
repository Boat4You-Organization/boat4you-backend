package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Record of a yacht-swap event detected on a partner reservation. Written by
 * `ReservationSyncService` when the partner's view of the reservation points
 * at a different yacht than our `reservation_flow.yacht_id`.
 *
 * INK1 detection produces either LOGGED_ONLY (auto-update disabled) or
 * AUTO_UPDATED (DB reconciled). MANUAL_REVIEW means the partner yacht is not
 * in our catalogue yet — admin must run a catalogue sync or resolve manually.
 */
@Entity
@Table(name = "reservation_yacht_swap_audit")
open class ReservationYachtSwapAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @NotNull
    @Column(name = "reservation_flow_id", nullable = false)
    open var reservationFlowId: Long? = null

    @NotNull
    @Column(name = "previous_yacht_id", nullable = false)
    open var previousYachtId: Long? = null

    @NotNull
    @Column(name = "previous_external_yacht_id", nullable = false)
    open var previousExternalYachtId: Long? = null

    @Column(name = "new_yacht_id")
    open var newYachtId: Long? = null

    @NotNull
    @Column(name = "new_external_yacht_id", nullable = false)
    open var newExternalYachtId: Long? = null

    @NotNull
    @Column(name = "external_system_id", nullable = false)
    open var externalSystemId: Int? = null

    @NotNull
    @Column(name = "detected_at", nullable = false)
    open var detectedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "action", nullable = false, length = 30)
    open var action: YachtSwapAction? = null

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    open var notes: String? = null

    @Column(name = "acknowledged_at")
    open var acknowledgedAt: Instant? = null
}
