package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class OptionExpiryService(
    private val emailService: EmailService,
    private val reservationRepository: ReservationRepository,
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")

    @Transactional(readOnly = true)
    fun send24HourOptionExpirationReminder() {
        val startTime = LocalDateTime.now().plusHours(23)
        val endTime = LocalDateTime.now().plusHours(24)

        val reservations = reservationRepository.findExpiringReservations(startTime, endTime, OfferStatus.OPTION)

        if (reservations.isEmpty()) {
            log.info("No users with expiring options found.")
            return
        }

        reservations.forEach { reservation ->
            val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
            val variables =
                mapOf(
                    "userName" to "Dear ${reservation.reservationFlow!!.getFullName()},",
                    "expiryHours" to "24h",
                    "yachtImageUrl" to yachtImageUrl,
                    "yachtName" to reservation.reservationFlow!!.yacht!!.name,
                    "yachtModel" to reservation.reservationFlow!!.yacht!!.model?.name,
                    "locationFrom" to reservation.locationFrom!!.name,
                    "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
                    "expiryDate" to reservation.optionExpiresAt!!.format(dateFormatter),
                    "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                    "reservationId" to reservation.id!!,
                    "publicUrl" to serverHostPublic,
                )

            emailService.sendEmail(
                recipients = listOf(reservation.reservationFlow!!.email!!),
                subject = "Reminder: Reservation option about to expire in 24 hours",
                templateName = "email/optionExpiryReminder",
                variables = variables,
            )
        }
    }

    @Transactional(readOnly = true)
    fun send48HourOptionExpirationReminder() {
        val startTime = LocalDateTime.now().plusHours(47)
        val endTime = LocalDateTime.now().plusHours(48)

        val reservations = reservationRepository.findExpiringReservations(startTime, endTime, OfferStatus.OPTION)

        if (reservations.isEmpty()) {
            log.info("No users with expiring options found.")
            return
        }

        reservations.forEach { reservation ->
            val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
            val variables =
                mapOf(
                    "userName" to "Dear ${reservation.reservationFlow!!.getFullName()},",
                    "expiryHours" to "48h",
                    "yachtImageUrl" to yachtImageUrl,
                    "yachtName" to reservation.reservationFlow!!.yacht!!.name,
                    "yachtModel" to reservation.reservationFlow!!.yacht!!.model?.name,
                    "locationFrom" to reservation.locationFrom!!.name,
                    "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
                    "expiryDate" to reservation.optionExpiresAt!!.format(dateFormatter),
                    "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                    "reservationId" to reservation.id!!,
                    "publicUrl" to serverHostPublic,
                )

            emailService.sendEmail(
                recipients = listOf(reservation.reservationFlow!!.email!!),
                subject = "Reminder: Reservation option about to expire in 48 hours",
                templateName = "email/optionExpiryReminder",
                variables = variables,
            )
        }
    }

    @Transactional(readOnly = false)
    fun syncExpiredOptions() {
        val expiredReservations =
            reservationRepository.findAllBySysStatusAndOptionExpiresAtBefore(
                ReservationStatus.OPTION,
                LocalDateTime.now(),
            )
        log.trace("Checking status for expired reservations: {}", expiredReservations.size)
        expiredReservations.forEach { reservation ->
            val extReservation = reservationIntegrationService.getExternalReservation(reservation.id!!)
            if (extReservation.calculatedSysStatus != ReservationStatus.OPTION) {
                log.trace("Updating reservation {} to status {}", reservation.id, extReservation.calculatedSysStatus)
                reservationMutationService.refreshReservation(
                    reservation.id!!,
                    extReservation,
                )

                sendExpiredEmail(reservation)
            } else {
                log.trace("Reservation ${reservation.id} is still in OPTION status.")
            }
        }
    }

    private fun sendExpiredEmail(reservation: Reservation) {
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val variables =
            mapOf(
                "message" to "Dear ${reservation.reservationFlow!!.user!!.getFullName()}, your reservation option has expired.",
                "publicUrl" to serverHostPublic,
                "yachtImageUrl" to yachtImageUrl,
                "yachtName" to reservation.reservationFlow!!.yacht!!.name,
                "yachtModel" to reservation.reservationFlow!!.yacht!!.model?.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to serverHostPublic + "/boat/" + reservation.reservationFlow!!.yacht!!.id,
            )

        emailService.sendEmail(
            recipients = listOf(reservation.reservationFlow!!.email!!),
            subject = "Your yacht reservation has expired!",
            templateName = "email/optionExpired",
            variables = variables,
        )
    }
}
