package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class PaymentPendingNotificationService(
    private val paymentPhasesRepository: ReservationPaymentPhaseRepository,
    private val reservationRepository: ReservationRepository,
    private val emailService: EmailService,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")

    @Transactional(readOnly = true)
    fun sendPaymentReminder(daysInAdvance: Long) {
        val pendingPayments = paymentPhasesRepository.findPendingPayments(LocalDate.now().plusDays(daysInAdvance))
        if (pendingPayments.isEmpty()) {
            log.info("No pending payments found")
        }

        val flowIdToReservationMap = reservationRepository.findByReservationFlowIdIn(pendingPayments.map { it.reservationFlow.id!! }).associateBy { it.reservationFlow!!.id!! }

        var skipped = 0
        var sent = 0
        pendingPayments.forEach { pendingPayment ->
            // Defensive guards: each lookup used `!!` and a single missing
            // reservation/yacht/email row used to throw NPE inside this
            // forEach, aborting the WHOLE batch — every customer past the
            // failure point lost their reminder until the next cron tick.
            // Skip-with-warn keeps the rest of the batch alive.
            try {
                val flow = pendingPayment.reservationFlow
                val reservation = flowIdToReservationMap[flow.id]
                val yacht = flow.yacht
                val email = flow.email
                if (reservation == null || yacht == null || email.isNullOrBlank()) {
                    log.warn(
                        "Skipping payment-pending email for flow={} — missing data " +
                            "(reservation={}, yacht={}, email blank={})",
                        flow.id, reservation?.id, yacht?.id, email.isNullOrBlank(),
                    )
                    skipped++
                    return@forEach
                }
                val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
                // Defensive HTML escape on the only user-controlled value that
                // reaches a th:utext node. Name comes from registration/guest
                // checkout where input validation is best-effort; escaping here
                // prevents a malicious '<script>' name from executing in the
                // customer's own email client (low-probability but cheap fix).
                val safeFullName = flow.getFullName()
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                val variables =
                    mapOf(
                        "message" to
                            "Dear $safeFullName, another payment for your booking is required. It will be <b>cancelled</b> if you do not pay the next installment <b>by ${
                                pendingPayment.deadline.format(dateFormatter)
                            }!</b>",
                        "publicUrl" to serverHostPublic,
                        "yachtImageUrl" to yachtImageUrl,
                        "yachtName" to yacht.name,
                        "yachtModel" to yacht.model?.name,
                        "locationFrom" to reservation.locationFrom?.name,
                        "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
                        "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                        "reservationId" to (reservation.reservationNumber ?: "${reservation.id!!}"),
                    )

                emailService.sendEmail(
                    recipients = listOf(email),
                    subject = "Another payment for your booking is required",
                    templateName = "email/reservationPaymentPending",
                    variables = variables,
                )
                sent++
            } catch (e: Exception) {
                log.error(
                    "Failed to send payment-pending email for flow={} — continuing batch",
                    pendingPayment.reservationFlow.id, e,
                )
                skipped++
            }
        }
        log.info("Payment-pending reminder batch: sent=$sent, skipped=$skipped, total=${pendingPayments.size}")
    }
}
