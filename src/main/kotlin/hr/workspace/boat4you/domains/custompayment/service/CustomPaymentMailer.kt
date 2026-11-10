package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment

/**
 * E-mails of the custom payments. Called by [CustomPaymentTx] INSIDE its transaction, each call wrapped in
 * `runCatching`: the implementation ([EmailCustomPaymentMailer]) goes through EmailService, which itself defers the SMTP
 * send to afterCommit — so a rolled-back transition never mails, and an implementation must NOT add its own afterCommit.
 */
interface CustomPaymentMailer {
    /** "Payment confirmation" to the payer (never "invoice"). */
    fun receipt(payment: CustomPayment)

    /** "Card payment received" to the office. */
    fun adminNotification(payment: CustomPayment)

    /** Something a person must look at (mismatch, late completion, refund, stuck attempt). */
    fun alert(
        payment: CustomPayment,
        kind: CustomPaymentAlertKind,
        reason: String,
    )
}

enum class CustomPaymentAlertKind {
    /** Mollie reports a completed payment that does not match the row. */
    NEEDS_REVIEW,

    /** Paid after the row had already been closed as FAILED / CANCELLED / NEEDS_REVIEW. */
    LATE_COMPLETION,

    /** Money was refunded from the Mollie dashboard. */
    REFUND,

    /** Still pending 48 h after it was created. */
    STALE,
}
