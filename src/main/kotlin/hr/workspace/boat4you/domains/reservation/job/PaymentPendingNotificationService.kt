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

        pendingPayments.forEach { pendingPayment ->
            val reservation = flowIdToReservationMap[pendingPayment.reservationFlow.id!!]!!
            val yachtImageUrl = pendingPayment.reservationFlow.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
            val variables =
                mapOf(
                    "message" to
                        "Dear ${pendingPayment.reservationFlow.getFullName()}, another payment for your booking is required. It will be <b>cancelled</b> if you do not pay the next installment <b>by ${
                            pendingPayment.deadline.format(
                                dateFormatter,
                            )
                        }!</b>",
                    "publicUrl" to serverHostPublic,
                    "yachtImageUrl" to yachtImageUrl,
                    "yachtName" to pendingPayment.reservationFlow.yacht!!.name,
                    "yachtModel" to pendingPayment.reservationFlow.yacht!!.model?.name,
                    "locationFrom" to reservation.locationFrom!!.name,
                    "viewBoatUrl" to serverHostPublic + "/boat/" + pendingPayment.reservationFlow.yacht!!.id,
                    "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                    "reservationId" to reservation.id!!,
                )

            emailService.sendEmail(
                recipients = listOf(pendingPayment.reservationFlow.email!!),
                subject = "Another payment for your booking is required",
                templateName = "email/reservationPaymentPending",
                variables = variables,
            )
        }
    }
}
