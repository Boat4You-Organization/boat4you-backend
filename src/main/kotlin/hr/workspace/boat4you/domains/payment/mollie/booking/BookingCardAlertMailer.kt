package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.reservation.service.BookingFailureAlertService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Office alert for an online-booking card payment a person must look at (money arrived but it does not match, a phase
 * was already paid = double payment, or the booking could not be confirmed after the payment). Same plain report mail
 * as BookingFailureAlertService, to every admin address. Never throws: an alert that cannot be queued is logged.
 */
@Component
class BookingCardAlertMailer(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun alert(
        order: ReservationCardOrder,
        title: String,
        reason: String,
    ) {
        runCatching {
            val recipients = userRepository.findAllAdminEmailAddresses()
            if (recipients.isEmpty()) {
                log.warn("Mollie booking order {}: alert '{}' not sent - no admin e-mail addresses", order.providerOrderId, title)
                return
            }
            val amount = "EUR " + BigDecimal.valueOf(order.amountMinor, 2).toPlainString()
            val body =
                buildString {
                    appendLine("$title.")
                    appendLine()
                    appendLine("Reason:       ${reason.replace(LINE_BREAKS, " ")}")
                    appendLine()
                    appendLine("Reservation:  ${order.reference} (id ${order.reservationId ?: "-"})")
                    appendLine("Charged:      $amount (base ${order.baseAmount}, surcharge ${order.surchargeAmount}, method ${order.methodOrCard})")
                    appendLine("Phases:       ${order.phaseIds.ifEmpty { "-" }}")
                    appendLine("Mollie:       payment ${order.providerOrderId} (Mollie dashboard -> Payments)")
                    appendLine("Ledger:       id ${order.id}, status ${order.status}")
                    order.reservationId?.let { appendLine("Admin:        ${serverHostAdminPublic.trimEnd('/')}/bookings/$it") }
                    appendLine()
                    append("Check the payment in the Mollie dashboard before telling the client anything; refunds are made there.")
                }
            emailService.sendEmail(
                recipients = recipients,
                subject = "⚠️ $title: ${order.reference}, $amount".take(MAX_SUBJECT_CHARS),
                templateName = BookingFailureAlertService.TEMPLATE,
                variables = mapOf("title" to title, "reportBody" to body),
            )
        }.onFailure { log.error("Mollie booking order {}: alert '{}' could not be queued", order.providerOrderId, title, it) }
    }

    private companion object {
        const val MAX_SUBJECT_CHARS = 140
        val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
