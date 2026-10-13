package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderStates
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderTypes

/**
 * What a Revolut order means for our custom payment row (pure; no I/O). [Complete] ONLY when every check matches —
 * `type=payment`, `state=completed`, the exact amount in EUR, nothing outstanding and our public token as
 * `merchant_order_data.reference`. A completed order that fails any check is [NeedsReview] (money arrived, a person
 * decides); `failed` / `cancelled` end the attempt; every other state is still in progress.
 */
sealed interface CustomPaymentVerdict {
    data class Complete(
        val paymentId: String?,
    ) : CustomPaymentVerdict

    data class NeedsReview(
        val reason: String,
    ) : CustomPaymentVerdict

    data object Failed : CustomPaymentVerdict

    data object Cancelled : CustomPaymentVerdict

    data object StillPending : CustomPaymentVerdict

    companion object {
        /** Payment states that mean "money taken" (automatic capture ends in `completed`; older orders say `captured`). */
        private val SUCCESSFUL_PAYMENT_STATES = setOf("completed", "captured", "authorised")
        private const val MAX_PAYMENT_ID_LENGTH = 64

        fun of(
            order: RevolutOrder,
            payment: CustomPayment,
        ): CustomPaymentVerdict = of(order, payment.amountMinor, payment.publicToken.toString())

        fun of(
            order: RevolutOrder,
            expectedAmountMinor: Long,
            expectedReference: String,
        ): CustomPaymentVerdict =
            when (order.state.lowercase()) {
                RevolutOrderStates.COMPLETED -> completed(order, expectedAmountMinor, expectedReference)
                RevolutOrderStates.FAILED -> Failed
                RevolutOrderStates.CANCELLED -> Cancelled
                else -> StillPending
            }

        private fun completed(
            order: RevolutOrder,
            expectedAmountMinor: Long,
            expectedReference: String,
        ): CustomPaymentVerdict {
            val mismatches =
                listOfNotNull(
                    "type ${order.type}".takeIf { order.type.lowercase() != RevolutOrderTypes.PAYMENT },
                    "amount ${order.amount} instead of $expectedAmountMinor".takeIf { order.amount != expectedAmountMinor },
                    "currency ${order.currency}".takeIf { order.currency.uppercase() != CustomPayment.CURRENCY_EUR },
                    "outstanding ${order.outstandingAmount}".takeIf { (order.outstandingAmount ?: 0L) != 0L },
                    "reference does not match".takeIf { order.merchantOrderData?.reference != expectedReference },
                )
            if (mismatches.isNotEmpty()) return NeedsReview("Revolut order completed but " + mismatches.joinToString(", "))
            val paymentId =
                (order.payments.lastOrNull { it.state.lowercase() in SUCCESSFUL_PAYMENT_STATES } ?: order.payments.lastOrNull())
                    ?.id
                    ?.take(MAX_PAYMENT_ID_LENGTH)
            return Complete(paymentId)
        }
    }
}
