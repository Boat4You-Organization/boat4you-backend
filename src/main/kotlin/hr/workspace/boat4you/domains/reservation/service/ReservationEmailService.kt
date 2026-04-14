package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@Service
@Transactional(readOnly = true)
class ReservationEmailService(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
) {
    fun sendOptionCreatedEmail(reservationId: Long) {
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val variables =
            mapOf(
                "mainMessage" to "Almost done, ${reservation.reservationFlow!!.user!!.getFullName()}! We just need a few more details to confirm your booking.",
                "reservationId" to "${reservation.id!!}",
                "yachtImageUrl" to yachtImageUrl,
                "yachtName" to reservation.reservationFlow!!.yacht!!.name,
                "yachtModel" to reservation.reservationFlow!!.yacht!!.model?.name,
                "locationFrom" to "${reservation.locationFrom!!.name}",
                "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
                "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
                "dropOffDateHourPeriod" to
                    "${reservation.dateTo!!.format(dateFormatter)}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
                "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
                "pickupLocation" to reservation.locationFrom!!.name,
                "totalPrice" to reservation.totalPrice.toString() +
                    Currency
                        .getInstance(reservation.currency)
                        .getSymbol(Locale.getDefault())
                        .toString(),
                "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(reservation.reservationFlow!!.email!!),
            subject = "Your yacht reservation option has been opened!",
            templateName = "email/fewMoreDetails",
            variables = variables,
        )

        emailService.sendEmail(
            recipients = adminEmails,
            subject = "Reservation has been opened for user ${reservation.reservationFlow!!.user!!.getFullName()}!",
            templateName = "email/fewMoreDetails",
            variables = variables,
        )
    }

    @Transactional(readOnly = true)
    fun sendConfirmationForReserved(
        reservation: ReservationDto,
        paymentType: PaymentType,
    ) {
        val reservation = reservationRepository.findById(reservation.id).orElseThrow()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val variables =
            mapOf(
                "mainMessage" to "Thank you ${reservation.reservationFlow!!.user!!.getFullName()}. Your yacht reservation has been confirmed!",
                "reservationId" to "${reservation.id!!}",
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to reservation.reservationFlow!!.yacht!!.model?.name,
                "yachtName" to reservation.reservationFlow!!.yacht!!.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
                "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
                "dropOffDateHourPeriod" to
                    "${reservation.dateTo!!.format(
                        dateFormatter,
                    )}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
                "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
                "pickupLocation" to reservation.locationFrom!!.name,
                "totalPrice" to reservation.totalPrice.toString() +
                    Currency
                        .getInstance(reservation.currency)
                        .getSymbol(Locale.getDefault())
                        .toString(),
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(reservation.reservationFlow!!.email!!),
            subject = "Your yacht reservation ${reservation.id!!} has been confirmed!",
            templateName = if (paymentType == PaymentType.BANK_TRANSFER) "email/reservationConfirmed" else "email/reservationConfirmedPaymentCard",
            variables = variables,
        )
    }

    @Transactional(readOnly = true)
    fun sendRequestCancellation(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val variables =
            mapOf(
                "reservationId" to reservation.id!!,
                "message" to
                    "User ${reservation.reservationFlow!!.user!!.getFullName()} has requested cancellation. Cancellation request: ${reservation.reservationFlow!!.cancelationRequest}",
                "reservationUrl" to serverHostAdminPublic + "/bookings/" + reservation.id,
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to reservation.reservationFlow!!.yacht?.model?.name,
                "yachtName" to reservation.reservationFlow!!.yacht?.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = adminEmails,
            subject = "Cancellation request",
            templateName = "email/cancellationRequest",
            variables = variables,
        )
    }
}
