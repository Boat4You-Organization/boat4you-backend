package hr.workspace.boat4you.domains.gdpr.services

import hr.workspace.boat4you.domains.catalouge.jpa.CustomOfferRepository
import hr.workspace.boat4you.domains.gdpr.dto.ExportedCustomOfferDto
import hr.workspace.boat4you.domains.gdpr.dto.ExportedGdprActivityDto
import hr.workspace.boat4you.domains.gdpr.dto.ExportedPaymentPhaseDto
import hr.workspace.boat4you.domains.gdpr.dto.ExportedReservationDto
import hr.workspace.boat4you.domains.gdpr.dto.ExportedUserDto
import hr.workspace.boat4you.domains.gdpr.dto.UserDataExportDto
import hr.workspace.boat4you.domains.gdpr.jpa.GdprAuditLogEntity
import hr.workspace.boat4you.domains.gdpr.jpa.GdprAuditLogRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

/**
 * GDPR Article 20 (Right to Data Portability) — assembles a structured JSON
 * export of every record we hold about the customer. Returned to the
 * frontend which serves it as a `Content-Disposition: attachment` download.
 *
 * Scope: user profile, every reservation chain (with payment phases +
 * cancellation context), custom-offer history, and the GDPR audit log
 * itself (so the customer sees their own request history). Anonymized rows
 * (post-Article-17 delete) export the tombstone state — Article 20 is
 * "give me what you currently hold", not "what you used to hold".
 */
@Service
class DataExportService(
    private val userRepository: UserRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val reservationRepository: ReservationRepository,
    private val customOfferRepository: CustomOfferRepository,
    private val gdprAuditRepository: GdprAuditLogRepository,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Transactional(readOnly = true)
    fun exportForUser(userId: Long): UserDataExportDto {
        val user = userRepository.findById(userId).getOrElse { throw UserDoesNotExistException() }
        logger.info("GDPR data export starting for user id={}", userId)

        val reservationFlows = reservationFlowRepository.findAllByUserId(userId)
        val flowIds = reservationFlows.mapNotNull { it.id }.toSet()
        val flowById = reservationFlows.associateBy { it.id!! }
        val reservationsList =
            if (flowIds.isEmpty()) emptyList() else reservationRepository.findByReservationFlowIdIn(flowIds)

        val reservations = reservationsList.map { res ->
            val flow = flowById[res.reservationFlow?.id]
            ExportedReservationDto(
                reservationId = res.id ?: 0L,
                reservationNumber = res.reservationNumber,
                status = res.status?.name,
                sysStatus = res.sysStatus?.name,
                externalCode = res.externalReservationCode,
                createdAt = res.externalCreatedAt,
                dateFrom = res.dateFrom,
                dateTo = res.dateTo,
                yachtName = flow?.yacht?.name,
                yachtModel = flow?.yacht?.model?.name,
                locationFrom = res.locationFrom?.name,
                locationTo = res.locationTo?.name,
                basePrice = res.basePrice,
                totalPrice = res.totalPrice,
                clientPrice = res.clientPrice,
                deposit = res.deposit,
                currency = res.currency,
                paymentNote = res.paymentNote,
                cancellationRequest = flow?.cancelationRequest,
                cancellationRequestAt = flow?.cancelationRequestAt,
                optionExpiresAt = res.optionExpiresAt,
                paymentPhases = flow?.paymentPhases?.map { phase ->
                    ExportedPaymentPhaseDto(
                        deadline = phase.deadline,
                        amount = phase.amount,
                        paidOn = phase.paidOn,
                        stripePaymentIntentId = phase.stripePaymentIntentId,
                    )
                } ?: emptyList(),
            )
        }

        val customOffers = customOfferRepository.findAllByUserId(userId).map { offer ->
            ExportedCustomOfferDto(
                id = offer.id ?: 0L,
                createdAt = offer.createdAt,
                status = null,
                totalPrice = null,
                currency = null,
            )
        }

        val gdprActivity = gdprAuditRepository.findAllByUserIdOrderByRequestedAtDesc(userId).map { entry ->
            ExportedGdprActivityDto(
                action = entry.action,
                requestedAt = entry.requestedAt,
                completedAt = entry.completedAt,
                notes = entry.notes,
            )
        }

        return UserDataExportDto(
            exportedAt = Instant.now(),
            user = ExportedUserDto(
                id = user.id ?: 0L,
                name = user.name,
                surname = user.surname,
                email = user.email,
                phoneNumber = user.phoneNumber,
                address = user.address,
                city = user.city,
                country = user.country,
                language = user.language?.name,
                currency = user.currency?.name,
                registrationStatus = user.registrationStatus.name,
                createdAt = null,
                deletedAt = user.deletedAt,
            ),
            reservations = reservations,
            customOffers = customOffers,
            gdprActivityLog = gdprActivity,
        )
    }

    companion object {
        fun filenameFor(userId: Long, exportedAt: Instant): String {
            return "boat4you-data-export-user-$userId-${exportedAt.epochSecond}.json"
        }
    }
}
