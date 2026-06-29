package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkReservationIntegrationService
import hr.workspace.boat4you.domains.external.model.ReservationData
import hr.workspace.boat4you.domains.external.nausys.service.NausysReservationIntegrationService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationFlowNotExists
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class ReservationIntegrationService(
    private val externalMappingRepository: ExternalMappingRepository,
    private val mmkReservationIntegrationService: MmkReservationIntegrationService,
    private val nausysReservationIntegrationService: NausysReservationIntegrationService,
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    @Value("\${application.external.reservation.disabled:false}")
    private val partnerReservationDisabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ReservationIntegrationService::class.java)

    fun createExternalReservation(reservationFlowId: Long): ReservationResponseWrapper {
        val reservationFlow =
            reservationFlowRepository
                .findById(reservationFlowId)
                .orElseThrow { ReservationFlowNotExists() }
        val yacht = reservationFlow.yacht!!
        val agency = yacht.agency!!

        if (partnerReservationDisabled) {
            log.warn(
                "GLOBAL kill switch active (application.external.reservation.disabled=true): " +
                    "skipping partner createOption for reservationFlow $reservationFlowId, agency ${agency.id}",
            )
            return buildSkipOptionResponse(reservationFlow)
        }

        if (agency.skipExternalSystem == true) {
            log.info(
                "Skipping partner createOption for agency ${agency.id} (skipExternalSystem=true); " +
                    "synthesizing OPTION wrapper for reservationFlow $reservationFlowId",
            )
            return buildSkipOptionResponse(reservationFlow)
        }

        val externalSystem = agency.primarySource!!.externalSystem!!
        val externalYachtId =
            externalMappingRepository.findBySystemIdAndExternalSystemAndType(
                yacht.id!!,
                externalSystem,
                Yacht::class.simpleName.toString(),
            ) ?: throw YachtDoesNotExistException()
        val offer = reservationFlow.offer!!

        if (offer.yacht!!.id != yacht.id) {
            throw IllegalArgumentException("Offer does not belong to the selected yacht")
        }

        val reservationExtras = getSelectedYachtExtras(reservationFlow, externalSystem)

        // MMK validates that POST /reservation dateFrom/dateTo exactly match the
        // offer's turn-around slot (e.g. 17:00 → 09:00). LocalTime.MIN/MAX caused
        // partner to reject with "Yacht not available in period." even when the
        // yacht was free. NauSys only reads .toLocalDate() so this is a no-op there.
        val startDateTime = LocalDateTime.of(offer.dateFrom!!, LocalTime.parse(offer.checkin!!))
        val endDateTime = LocalDateTime.of(offer.dateTo!!, LocalTime.parse(offer.checkout!!))
        val reservationData =
            ReservationData(
                startDate = startDateTime,
                endDate = endDateTime,
                externalYachtId = externalYachtId.externalId!!,
                externalAgencyId = agency.getExternalId()!!,
                name = reservationFlow.name!!,
                surname = reservationFlow.surname!!,
                email = reservationFlow.email!!,
                phone = reservationFlow.phoneNumber!!,
                selectedServices = reservationExtras,
                selectedEquipment = emptyList(),
            )
        val externalReservation =
            when (externalSystem.id!!) {
                ExternalSystemEnum.MMK.value -> {
                    mmkReservationIntegrationService.createOption(
                        reservationData,
                        fallbackLocationFrom = offer.locationFrom,
                        fallbackLocationTo = offer.locationTo,
                    )
                }

                ExternalSystemEnum.NAUSYS.value -> {
                    nausysReservationIntegrationService.createOption(
                        reservationData,
                        fallbackLocationFrom = offer.locationFrom,
                        fallbackLocationTo = offer.locationTo,
                    )
                }

                else -> {
                    throw RuntimeException()
                }
            }

        return externalReservation
    }

    fun confirmExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val agency = reservation.reservationFlow!!.yacht!!.agency!!

        if (partnerReservationDisabled) {
            log.warn(
                "GLOBAL kill switch active: skipping partner confirmReservation for reservation $reservationId",
            )
            return buildSkipConfirmResponse(reservation)
        }

        if (agency.skipExternalSystem == true) {
            log.info(
                "Skipping partner confirmReservation for reservation $reservationId " +
                    "(agency ${agency.id} skipExternalSystem=true); synthesizing RESERVED wrapper",
            )
            return buildSkipConfirmResponse(reservation)
        }

        // Stale skip-flow / fictitious bookings carry no partner-side option,
        // so there's nothing to confirm on the partner side. Synthesize the
        // RESERVED wrapper so the customer-side confirm flow (Stripe) succeeds.
        if (reservation.externalId == null) {
            log.info(
                "No external_id on reservation $reservationId — synthesizing RESERVED wrapper " +
                    "(skip-flow / fictitious booking with no partner option to confirm)",
            )
            return buildSkipConfirmResponse(reservation)
        }

        val externalSystem = agency.primarySource!!.externalSystem!!
        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.confirmReservation(
                    reservation.externalId!!,
                    fallbackLocationFrom = reservation.locationFrom,
                    fallbackLocationTo = reservation.locationTo,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.confirmReservation(
                    reservation.externalId!!,
                    reservation.externalReservationCode!!,
                    fallbackLocationFrom = reservation.locationFrom,
                    fallbackLocationTo = reservation.locationTo,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    fun deleteExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val agency = reservation.reservationFlow!!.yacht!!.agency!!

        if (partnerReservationDisabled) {
            log.warn(
                "GLOBAL kill switch active: skipping partner cancelOption for reservation $reservationId",
            )
            return buildSkipCancelResponse(reservation)
        }

        if (agency.skipExternalSystem == true) {
            log.info(
                "Skipping partner cancelOption for reservation $reservationId " +
                    "(agency ${agency.id} skipExternalSystem=true); synthesizing CANCELLED wrapper",
            )
            return buildSkipCancelResponse(reservation)
        }

        // Stale skip-flow / fictitious bookings carry no partner-side option
        // (external_id=null because skip flag was on at creation, or it's an
        // admin-only fictitious reservation). Nothing to cancel on the partner
        // side — synthesize the CANCELLED wrapper so admin can still mark it.
        if (reservation.externalId == null) {
            log.info(
                "No external_id on reservation $reservationId — synthesizing CANCELLED wrapper " +
                    "(skip-flow / fictitious booking with no partner option to cancel)",
            )
            return buildSkipCancelResponse(reservation)
        }

        val externalSystem = agency.primarySource!!.externalSystem!!
        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.cancelOption(
                    reservation.externalId!!,
                    fallbackLocationFrom = reservation.locationFrom,
                    fallbackLocationTo = reservation.locationTo,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.cancelOption(
                    reservation.externalId!!,
                    reservation.externalReservationCode!!,
                    fallbackLocationFrom = reservation.locationFrom,
                    fallbackLocationTo = reservation.locationTo,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    /**
     * B2 compensation: release a partner option created in step 2 whose local
     * reservation row never committed (step 3 threw) — so there is no
     * reservationId for [deleteExternalReservation]. Cancels straight from the
     * createOption wrapper's external identifiers. Best-effort; no-op when there
     * is nothing on the partner side (skip-flow / kill-switch / null externalId).
     */
    fun cancelExternalOptionByWrapper(
        reservationFlowId: Long,
        wrapper: ReservationResponseWrapper,
    ) {
        val externalId = wrapper.externalId ?: return
        val reservationFlow =
            reservationFlowRepository
                .findById(reservationFlowId)
                .orElseThrow { ReservationFlowNotExists() }
        val agency = reservationFlow.yacht!!.agency!!
        if (partnerReservationDisabled || agency.skipExternalSystem == true) {
            return
        }
        when (agency.primarySource!!.externalSystem!!.id!!) {
            ExternalSystemEnum.MMK.value ->
                mmkReservationIntegrationService.cancelOption(
                    externalId,
                    fallbackLocationFrom = wrapper.locationFrom,
                    fallbackLocationTo = wrapper.locationTo,
                )

            ExternalSystemEnum.NAUSYS.value ->
                nausysReservationIntegrationService.cancelOption(
                    externalId,
                    wrapper.externalCode!!,
                    fallbackLocationFrom = wrapper.locationFrom,
                    fallbackLocationTo = wrapper.locationTo,
                )

            else -> {}
        }
    }

    fun getExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val externalSystem = reservation.reservationFlow!!.yacht!!.agency!!.primarySource!!.externalSystem!!

        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.getReservation(
                    reservation.externalId!!,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.getReservation(
                    reservation.externalId!!,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    fun getExternalReservation(
        externalSystem: ExternalSystemEnum,
        externalReservationId: Long,
    ): ReservationResponseWrapper {
        return when (externalSystem) {
            ExternalSystemEnum.MMK -> {
                mmkReservationIntegrationService.getReservation(
                    externalReservationId,
                )
            }

            ExternalSystemEnum.NAUSYS -> {
                nausysReservationIntegrationService.getReservation(
                    externalReservationId,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    private fun getSelectedYachtExtras(
        reservationFlow: ReservationFlow,
        externalSystem: ExternalSystem,
    ): List<Long> {
        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                emptyList()
            }

            ExternalSystemEnum.NAUSYS.value -> {
                reservationFlow.reservationExtras.map { it.externalId!! }
            }

            else -> {
                throw RuntimeException("Unsupported external system: ${externalSystem.id}")
            }
        }
    }

    /**
     * Synthesize the response we'd normally get from `createOption` on the
     * partner. Used when the agency has `skipExternalSystem=true` — the
     * agency manages bookings outside our integration (e.g. ručno na partner
     * strani) and we just record the booking in our DB. `external_id` and
     * `external_code` are intentionally null so sync jobs ignore the row
     * (yacht swap detection, MMK availability sync, etc. all key off
     * `external_id IS NOT NULL`).
     */
    private fun buildSkipOptionResponse(reservationFlow: ReservationFlow): ReservationResponseWrapper {
        val yacht = reservationFlow.yacht!!
        val offer = reservationFlow.offer
            ?: throw IllegalStateException(
                "skipExternalSystem flow requires an offer on the reservation flow " +
                    "(reservationFlowId=${reservationFlow.id}). For purely admin-entered bookings " +
                    "without an offer, use the fictitious-reservation endpoint instead.",
            )
        val now = LocalDateTime.now()
        val locationFrom = offer.locationFrom ?: yacht.location
            ?: throw IllegalStateException(
                "skipExternalSystem flow needs a pickup location: neither the offer nor the yacht has one " +
                    "(yachtId=${yacht.id}, offerId=${offer.id})",
            )
        val locationTo = offer.locationTo ?: locationFrom
        val totalPrice = offer.totalPrice ?: offer.clientPrice ?: BigDecimal.ZERO
        val clientPrice = offer.clientPrice ?: totalPrice

        return ReservationResponseWrapper(
            externalId = null,
            externalCode = null,
            dateFrom = offer.dateFrom!!.atStartOfDay(),
            dateTo = offer.dateTo!!.atStartOfDay(),
            createdAt = now,
            // No partner option to expire — admin/customer flow drives
            // confirm/cancel manually. 48h TTL kept for parity with the
            // option lifecycle the customer UI assumes.
            expiresAt = now.plusHours(48),
            product = offer.product!!,
            locationFromId = locationFrom.id!!,
            locationToId = locationTo.id!!,
            locationFrom = locationFrom,
            locationTo = locationTo,
            status = OfferStatus.OPTION,
            externalStatus = "SKIP-OPTION",
            basePrice = clientPrice,
            discount = offer.totalDiscount ?: BigDecimal.ZERO,
            commission = null,
            agencyPrice = null,
            totalPrice = totalPrice,
            clientPrice = clientPrice,
            deposit = offer.deposit,
            currency = "EUR",
            paymentNote = null,
            bankDetails = null,
            note = "Agency manages bookings outside our system — no partner API call was made",
            extras = null,
            paymentPlan = null,
            responseBody = null,
            crewListUrl = null,
            yachtId = yacht.id!!,
            calculatedSysStatus = ReservationStatus.OPTION,
        )
    }

    private fun buildSkipConfirmResponse(reservation: Reservation): ReservationResponseWrapper {
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val offer = flow.offer
        val locationFrom = reservation.locationFrom ?: offer?.locationFrom ?: yacht.location
            ?: throw IllegalStateException("Cannot synthesize confirm response: no location on reservation/offer/yacht")
        val locationTo = reservation.locationTo ?: offer?.locationTo ?: locationFrom

        return ReservationResponseWrapper(
            externalId = null,
            externalCode = null,
            dateFrom = reservation.dateFrom!!,
            dateTo = reservation.dateTo!!,
            createdAt = reservation.externalCreatedAt ?: LocalDateTime.now(),
            expiresAt = null,
            product = reservation.product ?: offer?.product!!,
            locationFromId = locationFrom.id!!,
            locationToId = locationTo.id!!,
            locationFrom = locationFrom,
            locationTo = locationTo,
            status = OfferStatus.RESERVED,
            externalStatus = "SKIP-RESERVATION",
            basePrice = reservation.basePrice ?: BigDecimal.ZERO,
            discount = reservation.discount ?: BigDecimal.ZERO,
            commission = reservation.commission,
            agencyPrice = reservation.agencyPrice,
            totalPrice = reservation.totalPrice ?: BigDecimal.ZERO,
            clientPrice = reservation.clientPrice ?: BigDecimal.ZERO,
            deposit = reservation.deposit,
            currency = reservation.currency ?: "EUR",
            paymentNote = reservation.paymentNote,
            bankDetails = reservation.bankDetails,
            note = reservation.note,
            extras = null,
            paymentPlan = null,
            responseBody = null,
            crewListUrl = null,
            yachtId = yacht.id!!,
            calculatedSysStatus = ReservationStatus.RESERVATION,
        )
    }

    private fun buildSkipCancelResponse(reservation: Reservation): ReservationResponseWrapper {
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val offer = flow.offer
        val locationFrom = reservation.locationFrom ?: offer?.locationFrom ?: yacht.location
            ?: throw IllegalStateException("Cannot synthesize cancel response: no location on reservation/offer/yacht")
        val locationTo = reservation.locationTo ?: offer?.locationTo ?: locationFrom

        return ReservationResponseWrapper(
            externalId = null,
            externalCode = null,
            dateFrom = reservation.dateFrom!!,
            dateTo = reservation.dateTo!!,
            createdAt = reservation.externalCreatedAt ?: LocalDateTime.now(),
            expiresAt = null,
            product = reservation.product ?: offer?.product!!,
            locationFromId = locationFrom.id!!,
            locationToId = locationTo.id!!,
            locationFrom = locationFrom,
            locationTo = locationTo,
            status = OfferStatus.CANCELLED,
            externalStatus = "SKIP-CANCELLED",
            basePrice = reservation.basePrice ?: BigDecimal.ZERO,
            discount = reservation.discount ?: BigDecimal.ZERO,
            commission = reservation.commission,
            agencyPrice = reservation.agencyPrice,
            totalPrice = reservation.totalPrice ?: BigDecimal.ZERO,
            clientPrice = reservation.clientPrice ?: BigDecimal.ZERO,
            deposit = reservation.deposit,
            currency = reservation.currency ?: "EUR",
            paymentNote = reservation.paymentNote,
            bankDetails = reservation.bankDetails,
            note = reservation.note,
            extras = null,
            paymentPlan = null,
            responseBody = null,
            crewListUrl = null,
            yachtId = yacht.id!!,
            calculatedSysStatus = ReservationStatus.CANCELLED,
        )
    }
}
