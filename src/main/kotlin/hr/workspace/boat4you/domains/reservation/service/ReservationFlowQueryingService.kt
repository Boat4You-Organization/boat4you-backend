package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.reservation.dto.MyReservationDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtraRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationViewRepository
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class ReservationFlowQueryingService(
    private val reservationViewRepository: ReservationViewRepository,
    private val reservationExtraRepository: ReservationExtraRepository,
    private val reservationMappers: ReservationMappers,
    private val yachtRepository: YachtRepository,
) {
    fun getMyReservations(
        userId: Long,
        currency: CurrencyEnum,
    ): List<MyReservationsDto> {
        val reservations = reservationViewRepository.findAllByReservationUserId(userId)
        return reservations.map { reservationMappers.toMyReservationsResponse(it, currency) }
    }

    fun getMyReservationDetails(
        id: Long,
        userId: Long,
        language: LanguageEnum,
        currency: CurrencyEnum,
    ): MyReservationDetailsDto {
        val reservation =
            reservationViewRepository.findByReservationIdAndReservationUserId(id, userId)
                ?: throw RuntimeException("Reservation for user not found")
        val selectedExtras = reservationExtraRepository.findAllByReservationFlowId(reservation.reservationFlowId!!)
        val yacht = yachtRepository.findById(reservation.yachtId!!).get()

        return reservationMappers.toMyReservationDetailsResponse(
            reservation,
            selectedExtras,
            yacht,
            language,
            currency,
        )
    }

    fun getAllForAdmin(
        status: ReservationStatus?,
        userId: Long?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        reservationId: Long?,
        pageable: Pageable,
    ): Page<ReservationViewDto> {
        val startAt = dateFrom?.atStartOfDay()
        val endAt = dateTo?.atTime(23, 59, 59)
        return reservationViewRepository
            .findAllReservationsByParams(status, userId, startAt, endAt, reservationId, pageable)
            .map { reservationMappers.toShortDto(it) }
    }

    fun getReservationByIdForAdmin(
        id: Long,
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): ReservationViewDetailsDto {
        val reservation =
            reservationViewRepository
                .findById(id)
                .orElseThrow { IllegalArgumentException("Reservation with id $id not found") }
        val yacht = yachtRepository.findById(reservation.yachtId!!).get()

        val selectedExtras = reservationExtraRepository.findAllByReservationFlowId(reservation.reservationFlowId!!)

        return reservationMappers.toDetailsDto(reservation, yacht, selectedExtras, currency, language)
    }
}
