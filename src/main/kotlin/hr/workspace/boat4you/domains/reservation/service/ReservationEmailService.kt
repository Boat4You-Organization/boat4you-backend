package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationYachtSwapAudit
import hr.workspace.boat4you.domains.reservation.jpa.YachtSwapAction
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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
    private val settingsService: AdminSettingsService,
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

        // Display booking number in emails (e.g. "100176/2026"); fall back to
        // the internal id for historical rows that predate the feature.
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val variables =
            mapOf(
                "mainMessage" to "Almost done, ${reservation.reservationFlow!!.user!!.getFullName()}! We just need a few more details to confirm your booking.",
                "reservationId" to displayReservationRef,
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

        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val currencySymbol =
            Currency
                .getInstance(reservation.currency)
                .getSymbol(Locale.getDefault())
                .toString()

        // Bank-transfer flat fee is applied per inbound wire (Erste OUR
        // charge). We expose it in the confirmation so customers reconcile
        // the wire amount they sent with what the booking totals say — the
        // email template shows it only when > 0 and only on the bank path.
        val bankTransferFee: BigDecimal =
            if (paymentType == PaymentType.BANK_TRANSFER) {
                settingsService
                    .getSetting(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE)
                    .value
                    ?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            } else {
                BigDecimal.ZERO
            }

        val variables =
            mapOf(
                "mainMessage" to "Thank you ${reservation.reservationFlow!!.user!!.getFullName()}. Your yacht reservation has been confirmed!",
                "reservationId" to displayReservationRef,
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
                "totalPrice" to reservation.totalPrice.toString() + currencySymbol,
                "bankTransferFee" to bankTransferFee.toPlainString() + currencySymbol,
                "showBankTransferFee" to (bankTransferFee > BigDecimal.ZERO),
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(reservation.reservationFlow!!.email!!),
            subject = "Your yacht reservation $displayReservationRef has been confirmed!",
            templateName = if (paymentType == PaymentType.BANK_TRANSFER) "email/reservationConfirmed" else "email/reservationConfirmedPaymentCard",
            variables = variables,
        )
    }

    @Transactional(readOnly = true)
    fun sendRequestCancellation(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val variables =
            mapOf(
                "reservationId" to displayReservationRef,
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

    fun sendYachtSwapNotification(
        reservation: Reservation,
        audit: ReservationYachtSwapAudit,
    ) {
        val flow = reservation.reservationFlow ?: return
        val newYacht = flow.yacht ?: return
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"
        val yachtImageUrl = newYacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val customerMessage =
            when (audit.action) {
                YachtSwapAction.AUTO_UPDATED ->
                    "The charter agency has replaced the yacht on your reservation $displayReservationRef. " +
                        "Your new yacht is shown below. Dates, location and price remain the same. " +
                        "Please review the new yacht details and contact us if anything looks wrong."
                YachtSwapAction.MANUAL_REVIEW ->
                    "The charter agency has changed the yacht on your reservation $displayReservationRef. " +
                        "We're verifying the new yacht details and will contact you shortly. " +
                        "No action required from you right now."
                else ->
                    "A change was detected on your reservation $displayReservationRef. " +
                        "Our team is reviewing and will contact you shortly."
            }

        val customerVariables =
            mapOf(
                "reservationId" to displayReservationRef,
                "message" to customerMessage,
                "reservationUrl" to "$serverHostPublic/my-bookings/${reservation.id}",
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to newYacht.model?.name,
                "yachtName" to newYacht.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to "$serverHostPublic/boat/${newYacht.id}",
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(flow.email!!),
            subject = "Your yacht has been replaced on reservation $displayReservationRef",
            templateName = "email/yachtReplaced",
            variables = customerVariables,
        )

        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val adminMessage =
            "Yacht-swap detected on reservation $displayReservationRef. " +
                "Action: ${audit.action?.name}. " +
                "Previous yacht id: ${audit.previousYachtId} (external: ${audit.previousExternalYachtId}). " +
                "New yacht id: ${audit.newYachtId ?: "UNKNOWN"} (external: ${audit.newExternalYachtId}). " +
                "External system id: ${audit.externalSystemId}. " +
                (audit.notes?.let { "Notes: $it" } ?: "")

        val adminVariables =
            mapOf(
                "reservationId" to displayReservationRef,
                "message" to adminMessage,
                "reservationUrl" to "$serverHostAdminPublic/bookings/$displayReservationRef",
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to newYacht.model?.name,
                "yachtName" to newYacht.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to "$serverHostPublic/boat/${newYacht.id}",
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = adminEmails,
            subject = "[ADMIN] Yacht-swap detected on $displayReservationRef (${audit.action?.name})",
            templateName = "email/yachtReplaced",
            variables = adminVariables,
        )
    }
}
