package hr.workspace.boat4you.domains.custompayment.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment

/**
 * The part of a Mollie payment we keep in `custom_payment.order_snapshot` (pure). A whitelist: id, status, method,
 * amounts, currency, timestamps and the card label + last four digits. Nothing about the payer (no e-mail, name,
 * address, BIN, expiry) ever reaches our database through it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderSnapshot(
    val id: String,
    val type: String,
    val state: String,
    val amount: Long,
    val currency: String,
    val outstandingAmount: Long?,
    val refundedAmount: Long?,
    val createdAt: String,
    val updatedAt: String,
    val payments: List<PaymentSnapshot>,
    val method: String? = null,
    val chargedBackAmount: Long? = null,
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PaymentSnapshot(
        val id: String,
        val state: String,
        val createdAt: String?,
        val cardBrand: String?,
        val cardLastFour: String?,
    )

    fun toJson(): String = MAPPER.writeValueAsString(this)

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val CARD_BRAND = Regex("^[A-Za-z_ ]{1,32}$")
        private val LAST_FOUR = Regex("^[0-9]{4}$")
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9_.:-]{1,64}$")

        fun of(payment: MolliePayment): OrderSnapshot {
            val created = payment.createdAt?.toString() ?: "-"
            val updated = (payment.paidAt ?: payment.canceledAt ?: payment.failedAt ?: payment.expiredAt ?: payment.createdAt)?.toString() ?: created
            val state = safe(payment.status) ?: "-"
            return OrderSnapshot(
                id = safe(payment.id) ?: "-",
                type = "payment",
                state = state,
                amount = payment.amount.toMinorOrNull() ?: -1L,
                currency = safe(payment.amount.currency) ?: "-",
                outstandingAmount = null,
                refundedAmount = payment.amountRefunded?.toMinorOrNull(),
                createdAt = created,
                updatedAt = updated,
                payments =
                    listOf(
                        PaymentSnapshot(
                            id = safe(payment.id) ?: "-",
                            state = state,
                            createdAt = payment.createdAt?.toString(),
                            cardBrand = payment.details?.cardLabel?.takeIf { CARD_BRAND.matches(it) },
                            cardLastFour = payment.details?.cardNumber?.takeIf { LAST_FOUR.matches(it) },
                        ),
                    ),
                method = safe(payment.method),
                chargedBackAmount = payment.amountChargedBack?.toMinorOrNull(),
            )
        }

        /** The stored `order_snapshot` back as an object (e-mails read the card brand + last four); null when absent or unreadable. */
        fun parseOrNull(json: String?): OrderSnapshot? = json?.takeIf { it.isNotBlank() }?.let { runCatching { MAPPER.readValue(it, OrderSnapshot::class.java) }.getOrNull() }

        /** Mollie ids / statuses / methods are plain tokens; anything else (unexpected input) is dropped, never stored. */
        private fun safe(value: String?): String? = value?.takeIf { SAFE_TOKEN.matches(it) }
    }
}
