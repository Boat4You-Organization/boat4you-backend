package hr.workspace.boat4you.domains.custompayment.service

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder

/**
 * The part of a Revolut order we keep in `custom_payment.order_snapshot` (pure). A whitelist: ids, type, states,
 * amounts, currency, timestamps and, per payment, the card brand + last four digits. Nothing about the payer (no
 * e-mail, name, address, BIN, expiry) ever reaches our database through it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
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
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    data class PaymentSnapshot(
        val id: String,
        val state: String,
        val createdAt: String?,
        val cardBrand: String?,
        val cardLastFour: String?,
    )

    fun toJson(): String = MAPPER.writeValueAsString(this)

    companion object {
        private const val MAX_PAYMENTS = 20
        private val MAPPER = jacksonObjectMapper()
        private val CARD_BRAND = Regex("^[A-Za-z_]{1,32}$")
        private val LAST_FOUR = Regex("^[0-9]{4}$")
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9_.:-]{1,64}$")

        fun of(order: RevolutOrder): OrderSnapshot =
            OrderSnapshot(
                id = safe(order.id) ?: "-",
                type = safe(order.type) ?: "-",
                state = safe(order.state) ?: "-",
                amount = order.amount,
                currency = safe(order.currency) ?: "-",
                outstandingAmount = order.outstandingAmount,
                refundedAmount = order.refundedAmount,
                createdAt = order.createdAt.toString(),
                updatedAt = order.updatedAt.toString(),
                payments =
                    order.payments.takeLast(MAX_PAYMENTS).map { payment ->
                        PaymentSnapshot(
                            id = safe(payment.id) ?: "-",
                            state = safe(payment.state) ?: "-",
                            createdAt = payment.createdAt?.toString(),
                            cardBrand = payment.paymentMethod?.cardBrand?.takeIf { CARD_BRAND.matches(it) },
                            cardLastFour = payment.paymentMethod?.cardLastFour?.takeIf { LAST_FOUR.matches(it) },
                        )
                    },
            )

        /** The stored `order_snapshot` back as an object (e-mails read the card brand + last four); null when absent or unreadable. */
        fun parseOrNull(json: String?): OrderSnapshot? = json?.takeIf { it.isNotBlank() }?.let { runCatching { MAPPER.readValue(it, OrderSnapshot::class.java) }.getOrNull() }

        /** Revolut ids / states are plain tokens; anything else (unexpected input) is dropped, never stored. */
        private fun safe(value: String?): String? = value?.takeIf { SAFE_TOKEN.matches(it) }
    }
}
