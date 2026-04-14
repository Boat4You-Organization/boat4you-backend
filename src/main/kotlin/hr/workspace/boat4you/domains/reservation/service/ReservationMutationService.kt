package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.services.OfferMutationService
import hr.workspace.boat4you.domains.reservation.dto.CancelReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ExternalReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ExternalReservationPaymentPlan
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import kotlin.jvm.optionals.getOrElse

@Service
class ReservationMutationService(
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val reservationMappers: ReservationMappers,
    private val offerMutationService: OfferMutationService,
) {
    @Transactional
    fun refreshReservation(
        reservationId: Long,
        externalReservation: ReservationResponseWrapper,
    ): ReservationDto {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()

        reservation.status = externalReservation.status
        reservation.externalStatus = externalReservation.externalStatus
        reservation.sysStatus = externalReservation.calculatedSysStatus

        return reservationMappers.toReservationDto(reservationRepository.save(reservation))
    }

    @Transactional
    fun createReservation(
        reservationFlowId: Long,
        externalReservation: ReservationResponseWrapper,
    ): ReservationDto {
        val reservationFlow = reservationFlowRepository.findById(reservationFlowId).get()

        // when joining to external reservation, reservation number is generated only if status is confirmed reservation
        val reservationNumber =
            if (externalReservation.calculatedSysStatus == ReservationStatus.RESERVATION) {
                generateReservationNumber()
            } else {
                null
            }

        val reservation =
            Reservation().apply {
                this.reservationFlow = reservationFlow
                this.dateFrom = externalReservation.dateFrom
                this.dateTo = externalReservation.dateTo
                this.externalId = externalReservation.externalId
                this.externalReservationCode = externalReservation.externalCode
                this.externalCreatedAt = externalReservation.createdAt
                this.createdAt = Instant.now()
                this.optionExpiresAt = externalReservation.expiresAt
                this.status = externalReservation.status
                this.externalStatus = externalReservation.externalStatus
                this.sysStatus = externalReservation.calculatedSysStatus // option ili option_waiting
                this.response = externalReservation.responseBody
                this.basePrice = externalReservation.basePrice
                this.discount = externalReservation.discount
                this.commission = externalReservation.commission
                this.totalPrice = externalReservation.totalPrice
                this.clientPrice = externalReservation.clientPrice
                this.deposit = externalReservation.deposit
                this.currency = externalReservation.currency
                this.paymentNote = externalReservation.paymentNote
                this.bankDetails = externalReservation.bankDetails
                this.note = externalReservation.note
                this.locationFrom = externalReservation.locationFrom
                this.locationTo = externalReservation.locationTo
                this.product = externalReservation.product
                this.reservationNumber = reservationNumber
            }

        createPaymentPlans(reservation, externalReservation)
        createReservationExtras(reservation, externalReservation)

        reservationRepository.save(reservation)

        offerMutationService.updateOfferStatus(reservationFlow.offer!!.id!!, OfferStatus.OPTION)

        return reservationMappers.toReservationDto(reservation)
    }

    private fun createPaymentPlans(
        reservation: Reservation,
        externalReservation: ReservationResponseWrapper,
    ) {
        externalReservation.paymentPlan?.forEach {
            val paymentPlan = ExternalReservationPaymentPlan()
            paymentPlan.reservation = reservation
            paymentPlan.date = it.date
            paymentPlan.amount = it.amount
            reservation.externalReservationPaymentPlans.add(paymentPlan)
        }
    }

    private fun createReservationExtras(
        reservation: Reservation,
        externalReservation: ReservationResponseWrapper,
    ) {
        externalReservation.extras?.forEach {
            val externalReservationExtra = ExternalReservationExtra()
            externalReservationExtra.reservation = reservation
            externalReservationExtra.externalId = it.externalId
            externalReservationExtra.name = it.name
            externalReservationExtra.quantity = it.quantity?.toBigDecimal()
            externalReservationExtra.unit = it.unit
            externalReservationExtra.price = it.price
            externalReservationExtra.payableInBase = it.payableInBase
            reservation.externalReservationExtras.add(externalReservationExtra)
        }
    }

    @Transactional
    fun confirmReservation(
        reservationId: Long,
        externalReservation: ReservationResponseWrapper,
        paymentPhaseIds: List<Long>? = null,
    ): ReservationDto {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()

        reservation.status = externalReservation.status
        reservation.externalStatus = externalReservation.externalStatus
        reservation.sysStatus = ReservationStatus.RESERVATION
        reservation.reservationNumber = generateReservationNumber()
        reservation.crewListUrl = externalReservation.crewListUrl

        val reservationFlow = reservation.reservationFlow!!
        offerMutationService.updateOfferStatus(reservationFlow.offer!!.id!!, OfferStatus.RESERVED)

        if (paymentPhaseIds != null) {
            reservationFlow.paymentPhases.forEach {
                if (it.id!! in paymentPhaseIds) {
                    it.paidOn = Instant.now()
                }
            }
        }

        return reservationMappers.toReservationDto(reservation)
    }

    @Transactional
    fun cancelReservation(
        reservationId: Long,
        externalReservation: ReservationResponseWrapper,
    ): ReservationDto {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        reservation.status = externalReservation.status
        reservation.externalStatus = externalReservation.externalStatus
        reservation.sysStatus = ReservationStatus.CANCELLED
        reservationRepository.save(reservation)

        val reservationFlow = reservation.reservationFlow!!
        offerMutationService.updateOfferStatus(reservationFlow.offer!!.id!!, OfferStatus.FREE)

        return reservationMappers.toReservationDto(reservation)
    }

    @Transactional
    fun updateReservationNumber(
        id: Long,
        reservationNumber: String,
    ): ReservationDto {
        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }

        reservation.reservationNumber = reservationNumber

        return reservationMappers.toReservationDto(reservationRepository.save(reservation))
    }

    private fun generateReservationNumber(): String {
        val currentYear = Year.now().value.toString()
        val latestReservationNumber = reservationRepository.findMaxReservationNumberForYear(currentYear)

        val nextNumber =
            if (latestReservationNumber != null) {
                val prefix = latestReservationNumber.substringBefore("/")
                prefix.toInt() + 1
            } else {
                1001
            }

        return "$nextNumber/$currentYear"
    }

    @Transactional
    fun createCancellationRequest(
        id: Long,
        cancelReservationDto: CancelReservationDto,
    ) {
        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = reservation.reservationFlow!!

        reservationFlow.cancelationRequest = cancelReservationDto.specialRequest
        reservationFlow.cancelationRequestAt = LocalDateTime.now()

        reservationFlowRepository.save(reservationFlow)
    }
}
