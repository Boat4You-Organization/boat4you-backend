package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Office alert for a Stripe Checkout payment that arrives for a phase which is already paid (by a Mollie card payment
 * or a payment the office recorded) — a second payment of the same instalment. Plain report mail to every admin
 * address, like BookingFailureAlertService. Never throws.
 */
@Component
class CardDoublePaymentAlert(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun stripeAfterAnotherPayment(
        reservationId: Long,
        stripeSessionId: String,
        stripePaymentIntentId: String?,
        phaseIds: List<Long>,
    ) {
        runCatching {
            val recipients = userRepository.findAllAdminEmailAddresses()
            if (recipients.isEmpty()) {
                log.warn("Double payment alert for reservation {} not sent - no admin e-mail addresses", reservationId)
                return
            }
            val title = "DOUBLE PAYMENT: Stripe payment for an already paid instalment"
            val body =
                buildString {
                    appendLine("A Stripe card payment was completed for an instalment that was already paid.")
                    appendLine("Nothing was changed in the booking. Refund one of the two payments.")
                    appendLine()
                    appendLine("Reservation:  id $reservationId")
                    appendLine("Phases:       ${phaseIds.joinToString(",")}")
                    appendLine("Stripe:       session $stripeSessionId, payment intent ${stripePaymentIntentId ?: "-"}")
                    append("Admin:        ${serverHostAdminPublic.trimEnd('/')}/reservations/$reservationId")
                }
            emailService.sendEmail(
                recipients = recipients,
                subject = "⚠️ $title (reservation $reservationId)",
                templateName = BookingFailureAlertService.TEMPLATE,
                variables = mapOf("title" to title, "reportBody" to body),
            )
        }.onFailure { log.error("Double payment alert for reservation {} could not be queued", reservationId, it) }
    }
}
