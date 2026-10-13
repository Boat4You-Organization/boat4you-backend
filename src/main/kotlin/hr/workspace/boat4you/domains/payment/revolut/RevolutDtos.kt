package hr.workspace.boat4you.domains.payment.revolut

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.time.Instant

/*
 * Wire models of the Revolut Merchant API (snake_case JSON). Responses ignore unknown fields so a new Revolut field
 * never breaks a payment. String states/types instead of enums for the same reason (constants below).
 */

/**
 * `POST /api/orders` body. Deliberately WITHOUT `customer`: `customer.email` without an id creates a new Revolut
 * customer on every order, and the hosted page asks for the e-mail itself.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutCreateOrderRequest(
    /** Minor units (cents). */
    val amount: Long,
    val currency: String,
    val captureMode: String = RevolutCaptureMode.AUTOMATIC,
    val description: String? = null,
    /** Where the hosted page sends the payer after a SUCCESSFUL payment (failed/abandoned payers never return). */
    val redirectUrl: String? = null,
    val merchantOrderData: RevolutMerchantOrderData? = null,
    val metadata: Map<String, String>? = null,
    /** ISO-8601 duration (e.g. `PT1H`); a pending order then ends as `failed` + `ORDER_FAILED`. */
    val expirePendingAfter: String? = null,
    /** `automatic` | `forced` (3DS). */
    val enforceChallenge: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutMerchantOrderData(
    val reference: String? = null,
)

/** Order object returned by create / retrieve / cancel. A refund is an order of its own (`type=refund`). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutOrder(
    val id: String,
    val type: String,
    val state: String,
    /** Minor units (cents). */
    val amount: Long,
    val currency: String,
    val outstandingAmount: Long? = null,
    val refundedAmount: Long? = null,
    val checkoutUrl: String? = null,
    val merchantOrderData: RevolutMerchantOrderData? = null,
    /** For a refund order: the id of the refunded payment order. */
    val relatedOrderId: String? = null,
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    val payments: List<RevolutPayment> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutPayment(
    val id: String,
    val state: String,
    val createdAt: Instant? = null,
    val paymentMethod: RevolutPaymentMethod? = null,
)

/** Only the non-sensitive card fields we keep (brand + last four); everything else of `payment_method` is ignored. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutPaymentMethod(
    val type: String? = null,
    val cardBrand: String? = null,
    val cardLastFour: String? = null,
)

/** Webhook body `{event, order_id, merchant_order_ext_ref}`; there is no event id (dedupe is on our side). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevolutWebhookPayload(
    val event: String? = null,
    val orderId: String? = null,
)

object RevolutCaptureMode {
    const val AUTOMATIC = "automatic"
    const val MANUAL = "manual"
}

object RevolutOrderTypes {
    const val PAYMENT = "payment"
    const val REFUND = "refund"
}

object RevolutOrderStates {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val AUTHORISED = "authorised"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}

object RevolutWebhookEvents {
    const val ORDER_COMPLETED = "ORDER_COMPLETED"
    const val ORDER_AUTHORISED = "ORDER_AUTHORISED"
    const val ORDER_CANCELLED = "ORDER_CANCELLED"
    const val ORDER_FAILED = "ORDER_FAILED"
    const val ORDER_PAYMENT_DECLINED = "ORDER_PAYMENT_DECLINED"
    const val ORDER_PAYMENT_FAILED = "ORDER_PAYMENT_FAILED"
}
