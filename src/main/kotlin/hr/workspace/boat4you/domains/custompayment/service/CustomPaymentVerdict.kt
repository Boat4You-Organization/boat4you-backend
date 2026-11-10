package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentStatuses

/**
 * What a Mollie payment means for our custom payment row (pure; no I/O). [Complete] ONLY when every check matches —
 * `status=paid`, the exact amount in EUR and our public token as `metadata.customPaymentToken`. A paid payment that
 * fails any check is [NeedsReview] (money arrived, a person decides); `failed` / `expired` end the attempt as FAILED,
 * `canceled` as CANCELLED; `open` / `pending` / `authorized` are still in progress.
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
        const val TOKEN_METADATA_KEY = "customPaymentToken"
        private const val MAX_PAYMENT_ID_LENGTH = 64

        fun of(
            payment: MolliePayment,
            row: CustomPayment,
        ): CustomPaymentVerdict = of(payment, row.amountMinor, row.publicToken.toString())

        fun of(
            payment: MolliePayment,
            expectedAmountMinor: Long,
            expectedReference: String,
        ): CustomPaymentVerdict =
            when (payment.status.lowercase()) {
                MolliePaymentStatuses.PAID -> paid(payment, expectedAmountMinor, expectedReference)
                MolliePaymentStatuses.FAILED, MolliePaymentStatuses.EXPIRED -> Failed
                MolliePaymentStatuses.CANCELED -> Cancelled
                else -> StillPending
            }

        private fun paid(
            payment: MolliePayment,
            expectedAmountMinor: Long,
            expectedReference: String,
        ): CustomPaymentVerdict {
            val amountMinor = payment.amount.toMinorOrNull()
            val mismatches =
                listOfNotNull(
                    "amount ${payment.amount.value} instead of $expectedAmountMinor cents".takeIf { amountMinor != expectedAmountMinor },
                    "currency ${payment.amount.currency}".takeIf { payment.amount.currency.uppercase() != CustomPayment.CURRENCY_EUR },
                    "reference does not match".takeIf { payment.metadataString(TOKEN_METADATA_KEY) != expectedReference },
                )
            if (mismatches.isNotEmpty()) return NeedsReview("Mollie payment paid but " + mismatches.joinToString(", "))
            return Complete(payment.id.take(MAX_PAYMENT_ID_LENGTH))
        }
    }
}
