package hr.workspace.boat4you.domains.payment.mollie

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/*
 * Wire models of the Mollie Payments API v2 (camelCase JSON). Responses ignore unknown fields so a new Mollie field
 * never breaks a payment; statuses / methods are plain strings for the same reason (constants below).
 */

/** Mollie money: currency + decimal STRING with exactly two decimals for EUR (`"1109.00"`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieAmount(
    val currency: String,
    val value: String,
) {
    /** Cents, or null when the value is not a plain decimal with at most two decimals. */
    fun toMinorOrNull(): Long? =
        runCatching { BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }.getOrNull()

    companion object {
        fun eur(minor: Long): MollieAmount = MollieAmount("EUR", BigDecimal.valueOf(minor, 2).toPlainString())
    }
}

/**
 * `POST /v2/payments` body. No customer object and no billing data: the hosted checkout asks for the card itself.
 * [method] limits the hosted page to the listed methods (Klarna / bank transfer never appear by accident).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MollieCreatePaymentRequest(
    val amount: MollieAmount,
    /** Shown on the checkout and (shortened) on the card statement. */
    val description: String,
    val redirectUrl: String,
    val cancelUrl: String? = null,
    /** Must be publicly reachable; omitted locally (no webhook, status is read on return instead). */
    val webhookUrl: String? = null,
    val locale: String? = null,
    val method: List<String>? = null,
    val metadata: Map<String, String>? = null,
    /** Required by Mollie for Klarna (with [lines]); never sent for cards. */
    val billingAddress: MollieBillingAddress? = null,
    /** Order lines; their `totalAmount`s must add up to [amount]. Required for Klarna. */
    val lines: List<MolliePaymentLine>? = null,
)

data class MollieBillingAddress(
    val givenName: String,
    val familyName: String,
    val email: String,
    val streetAndNumber: String,
    val postalCode: String,
    val city: String,
    val country: String,
)

/** One order line (`quantity` 1: we sell a service, not items). [type] `surcharge` marks the payment fee. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MolliePaymentLine(
    val description: String,
    val unitPrice: MollieAmount,
    val totalAmount: MollieAmount,
    val quantity: Int = 1,
    val type: String? = null,
) {
    companion object {
        /**
         * Lines for a payment of [baseMinor] + [surchargeMinor] cents: the service and, when charged, the payment fee as
         * its own `surcharge` line, so the lines always add up to the payment amount.
         */
        fun of(
            description: String,
            baseMinor: Long,
            surchargeMinor: Long,
            feeDescription: String,
        ): List<MolliePaymentLine> =
            buildList {
                add(MolliePaymentLine(description, MollieAmount.eur(baseMinor), MollieAmount.eur(baseMinor)))
                if (surchargeMinor > 0) add(MolliePaymentLine(feeDescription, MollieAmount.eur(surchargeMinor), MollieAmount.eur(surchargeMinor), type = "surcharge"))
            }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MolliePayment(
    val id: String,
    val mode: String? = null,
    val status: String,
    val amount: MollieAmount,
    val amountRefunded: MollieAmount? = null,
    val amountRemaining: MollieAmount? = null,
    val amountChargedBack: MollieAmount? = null,
    val description: String? = null,
    val method: String? = null,
    val metadata: Map<String, Any?>? = null,
    val details: MolliePaymentDetails? = null,
    val createdAt: Instant? = null,
    val paidAt: Instant? = null,
    val canceledAt: Instant? = null,
    val expiresAt: Instant? = null,
    val expiredAt: Instant? = null,
    val failedAt: Instant? = null,
    val isCancelable: Boolean? = null,
    @JsonProperty("_links")
    val links: MollieLinks? = null,
    /** Present only when the payment was read with `?embed=refunds,chargebacks` (admin details). */
    @JsonProperty("_embedded")
    val embedded: MollieEmbedded? = null,
) {
    val checkoutUrl: String? get() = links?.checkout?.href

    fun metadataString(key: String): String? = (metadata?.get(key) as? String)?.trim()?.ifEmpty { null }
}

/** Only the non-sensitive card fields we keep (label = brand, number = last four digits); everything else is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MolliePaymentDetails(
    val cardLabel: String? = null,
    val cardNumber: String? = null,
    val cardCountryCode: String? = null,
    /** `credit` | `debit` | `prepaid` | `deferred-debit`. */
    val cardFunding: String? = null,
    /** `consumer` | `business`. */
    val cardAudience: String? = null,
    /** Mollie's pricing bucket of the card (e.g. `intra-eu`, `intra-eu-corporate`, `other`). */
    val feeRegion: String? = null,
    /** Why a card payment failed (e.g. `insufficient_funds`), with Mollie's customer-facing sentence. */
    val failureReason: String? = null,
    val failureMessage: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieEmbedded(
    val refunds: List<MollieRefund>? = null,
    val chargebacks: List<MollieChargeback>? = null,
)

/** A refund of a payment (created in the Mollie dashboard; we never create one). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieRefund(
    val id: String,
    val amount: MollieAmount,
    /** `queued` | `pending` | `processing` | `refunded` | `failed` | `canceled`. */
    val status: String? = null,
    val description: String? = null,
    val createdAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieChargeback(
    val id: String,
    val amount: MollieAmount,
    val createdAt: Instant? = null,
    val reason: MollieChargebackReason? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieChargebackReason(
    val code: String? = null,
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieLinks(
    val checkout: MollieLink? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MollieLink(
    val href: String? = null,
)

object MolliePaymentStatuses {
    /** Created, no attempt finished yet — the payer is (or was) on the hosted page; a failed card attempt stays open. */
    const val OPEN = "open"

    /** The method is processing (e.g. Pay by Bank); money not confirmed yet. */
    const val PENDING = "pending"
    const val AUTHORIZED = "authorized"
    const val PAID = "paid"
    const val CANCELED = "canceled"
    const val EXPIRED = "expired"
    const val FAILED = "failed"
}

object MollieMethods {
    const val KLARNA = "klarna"
    const val CREDIT_CARD = "creditcard"
    const val APPLE_PAY = "applepay"
    const val PAY_BY_BANK = "paybybank"
}
