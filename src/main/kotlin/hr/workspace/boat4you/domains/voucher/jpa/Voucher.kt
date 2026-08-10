package hr.workspace.boat4you.domains.voucher.jpa

import hr.workspace.boat4you.domains.voucher.enums.VoucherStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * EUR 100 loyalty voucher, issued once per customer after their first
 * confirmed booking and redeemable on a future booking (Mario 10.8.2026).
 * Transferable — the code is the credential, `issuedToUserId` is audit only.
 * Deliberately NOT an AbstractEntity: Envers auditing would demand a
 * voucher_revisions table (same reasoning as AiChatSession).
 */
@Entity
@Table(name = "voucher")
open class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "code", length = 20, nullable = false)
    open var code: String? = null

    @Column(name = "value", nullable = false)
    open var value: BigDecimal? = null

    @Column(name = "currency", length = 3, nullable = false)
    open var currency: String = "EUR"

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: VoucherStatus = VoucherStatus.ACTIVE

    @Column(name = "valid_from", nullable = false)
    open var validFrom: LocalDate? = null

    @Column(name = "valid_to", nullable = false)
    open var validTo: LocalDate? = null

    @Column(name = "issued_to_user_id", nullable = false)
    open var issuedToUserId: Long? = null

    @Column(name = "issued_for_reservation_id")
    open var issuedForReservationId: Long? = null

    @Column(name = "used_on_reservation_flow_id")
    open var usedOnReservationFlowId: Long? = null

    @Column(name = "used_by_user_id")
    open var usedByUserId: Long? = null

    @Column(name = "used_at")
    open var usedAt: Instant? = null

    @Column(name = "revoked_at")
    open var revokedAt: Instant? = null

    @Column(name = "revoked_reason", length = 500)
    open var revokedReason: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()
}
