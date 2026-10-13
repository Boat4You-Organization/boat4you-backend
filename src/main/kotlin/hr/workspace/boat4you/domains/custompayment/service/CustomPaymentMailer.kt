package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * E-mails of the custom payments. Called by [CustomPaymentTx] INSIDE its transaction, each call wrapped in
 * `runCatching`: the real implementation goes through EmailService, which itself defers the SMTP send to afterCommit —
 * so a rolled-back transition never mails, and an implementation must NOT add its own afterCommit.
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
    /** Revolut reports a completed payment that does not match the row. */
    NEEDS_REVIEW,

    /** Paid after the row had already been closed as FAILED / CANCELLED / NEEDS_REVIEW. */
    LATE_COMPLETION,

    /** Money was refunded from the Revolut dashboard. */
    REFUND,

    /** Still pending 48 h after it was created. */
    STALE,
}

/**
 * Placeholder until the e-mail templates exist: logs what would be sent (ids and kinds only — no e-mail address,
 * no reference). Replaced by the EmailService-backed mailer.
 */
@Component
class LoggingCustomPaymentMailer : CustomPaymentMailer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun receipt(payment: CustomPayment) {
        log.info("Custom payment {} ({}): payment confirmation e-mail not implemented yet", payment.id, payment.site)
    }

    override fun adminNotification(payment: CustomPayment) {
        log.info("Custom payment {} ({}): office notification e-mail not implemented yet", payment.id, payment.site)
    }

    override fun alert(
        payment: CustomPayment,
        kind: CustomPaymentAlertKind,
        reason: String,
    ) {
        log.warn("Custom payment {} ({}) ALERT {}: {} (alert e-mail not implemented yet)", payment.id, payment.site, kind, reason)
    }
}
