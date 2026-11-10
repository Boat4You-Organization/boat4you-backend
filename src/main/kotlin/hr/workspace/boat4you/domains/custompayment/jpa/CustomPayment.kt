package hr.workspace.boat4you.domains.custompayment.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One card payment attempt from the secret `/pay/<linkKey>` page (table `custom_payment`, V9_80). Written only through
 * `CustomPaymentTx`; the status transitions live there. Plain columns, no relations: [reservationId] is an optional,
 * informational link and is never navigated.
 */
@Entity
@Table(name = "custom_payment")
open class CustomPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    /** `metadata.customPaymentToken` of the Mollie payment and the `?ref=` of the success page. */
    @Column(name = "public_token", nullable = false, updatable = false)
    open var publicToken: UUID = UUID.randomUUID()

    /** `CARD` (cards, Apple Pay, Pay by Bank) or `KLARNA` (V9_125); NULL = a row from before Klarna = CARD. */
    @Column(name = "method", length = 16, updatable = false)
    open var method: String? = null

    @Column(name = "site", length = 32, nullable = false)
    open var site: String = ""

    @Column(name = "reference", length = 64, nullable = false)
    open var reference: String = ""

    /** Cents. */
    @Column(name = "amount_minor", nullable = false)
    open var amountMinor: Long = 0

    @Column(name = "currency", length = 3, nullable = false)
    open var currency: String = CURRENCY_EUR

    @Column(name = "provider", length = 16, nullable = false)
    open var provider: String = PROVIDER_MOLLIE

    @Column(name = "provider_order_id", length = 64)
    open var providerOrderId: String? = null

    @Column(name = "provider_payment_id", length = 64)
    open var providerPaymentId: String? = null

    @Column(name = "checkout_url")
    open var checkoutUrl: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    open var status: CustomPaymentStatus = CustomPaymentStatus.PENDING

    @Column(name = "payer_email", length = 255, nullable = false)
    open var payerEmail: String = ""

    @Column(name = "client_ip", length = 64)
    open var clientIp: String? = null

    @Column(name = "locale", length = 5, nullable = false)
    open var locale: String = "en"

    @Column(name = "terms_version", length = 32, nullable = false)
    open var termsVersion: String = ""

    @Column(name = "accepted_at", nullable = false)
    open var acceptedAt: Instant = Instant.now()

    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "completed_at")
    open var completedAt: Instant? = null

    @Column(name = "last_checked_at")
    open var lastCheckedAt: Instant? = null

    @Column(name = "last_webhook_event", length = 64)
    open var lastWebhookEvent: String? = null

    @Column(name = "last_webhook_at")
    open var lastWebhookAt: Instant? = null

    /** Cents; only grows (CustomPaymentTx.applyRefund), never above [amountMinor]. */
    @Column(name = "refunded_amount_minor", nullable = false)
    open var refundedAmountMinor: Long = 0

    /** Mollie's running chargeback total in cents; only grows (CustomPaymentTx.applyChargeback). */
    @Column(name = "charged_back_amount_minor", nullable = false)
    open var chargedBackAmountMinor: Long = 0

    /** JSON of `OrderSnapshot` (whitelisted Mollie payment fields). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "order_snapshot")
    open var orderSnapshot: String? = null

    @Column(name = "reservation_id")
    open var reservationId: Long? = null

    @Column(name = "admin_note")
    open var adminNote: String? = null

    companion object {
        const val CURRENCY_EUR = "EUR"
        const val PROVIDER_MOLLIE = "MOLLIE"
    }
}
