package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDto
import hr.workspace.boat4you.domains.reservation.dto.UserExtReservationDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowQueryingService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Reservation Management", description = "Manage Reservations for Administrators")
@RestController
@RequestMapping("/admin/reservations")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminReservationController(
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationEmailService: ReservationEmailService,
    private val reservationFlowQueryingService: ReservationFlowQueryingService,
    private val reservationFlowMutationService: ReservationFlowMutationService,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val userRepository: UserRepository,
) {
    @Operation(summary = "Get all reservations")
    @GetMapping()
    fun getReservations(
        @RequestParam(name = "status", required = false) status: ReservationStatus?,
        @RequestParam(name = "userId", required = false) userId: Long?,
        @RequestParam(name = "dateFrom", required = false) dateFrom: LocalDate?,
        @RequestParam(name = "dateTo", required = false) dateTo: LocalDate?,
        @RequestParam(name = "reservationId", required = false) reservationId: Long?,
        @PageableDefault(
            sort = ["reservationCreatedAt"],
            direction = Sort.Direction.DESC,
        ) pageable: Pageable,
    ): ResponseEntity<PagedModel<ReservationViewDto>> {
        val reservations =
            reservationFlowQueryingService.getAllForAdmin(status, userId, dateFrom, dateTo, reservationId, pageable)
        return ResponseEntity.ok(PagedModel(reservations))
    }

    @Operation(summary = "Get reservation details by ID")
    @GetMapping("/{id}")
    fun getReservationDetails(
        @PathVariable id: Long,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<ReservationViewDetailsDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(null, user)
        val language = LanguageEnum.getLanguage(lang, user)

        val reservation = reservationFlowQueryingService.getReservationByIdForAdmin(id, currency, language)
        return ResponseEntity.ok(reservation)
    }

    @Operation(description = "Update reservation number")
    @PatchMapping("/{id}/updateReservationNumber")
    fun updateReservationNumber(
        @PathVariable id: Long,
        @RequestParam reservationNumber: String,
    ): ResponseEntity<ReservationDto> {
        return ResponseEntity.ok(reservationMutationService.updateReservationNumber(id, reservationNumber))
    }

    @Operation(description = "Refresh reservation status from external service")
    @PostMapping("/{id}/refresh")
    fun refreshReservation(
        @PathVariable id: Long,
    ): ResponseEntity<ReservationDto> {
        val externalReservation =
            reservationIntegrationService.getExternalReservation(id)
        val reservation = reservationMutationService.refreshReservation(id, externalReservation)
        return ResponseEntity.ok(reservation)
    }

    @Operation(description = "Confirm reservation and promote it to booking")
    @PutMapping("/{id}/{paymentPhaseIds}")
    fun confirmReservation(
        @PathVariable id: Long,
        @Parameter(description = "Payment phases which will be marked as paid") @PathVariable paymentPhaseIds: List<Long> = emptyList(),
    ): ResponseEntity<ReservationDto> {
        val externalReservation = reservationIntegrationService.confirmExternalReservation(id)
        val reservationResponse =
            reservationMutationService.confirmReservation(id, externalReservation, paymentPhaseIds)

        reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.BANK_TRANSFER)

        return ResponseEntity.ok(reservationResponse)
    }

    @Operation(description = "Mark payment phase(s) as paid. Does not promote a reservation to booking")
    @PostMapping("/{id}/markPaymentPhasePaid/{paymentPhaseIds}")
    fun markPaymentPhasePaid(
        @PathVariable id: Long,
        @Parameter(description = "Payment phases which will be marked as paid") @PathVariable paymentPhaseIds: List<Long> = emptyList(),
    ): ResponseEntity<List<PaymentPhaseDto>> {
        return ResponseEntity.ok(paymentPhasesService.markPaymentPhasePaid(id, paymentPhaseIds))
    }

    @Operation(description = "Cancel reservation")
    @DeleteMapping("/{id}")
    fun cancelReservation(
        @PathVariable id: Long,
    ): ResponseEntity<ReservationDto> {
        val externalReservation = reservationIntegrationService.deleteExternalReservation(id)
        val reservationResponse = reservationMutationService.cancelReservation(id, externalReservation)
        return ResponseEntity.ok(reservationResponse)
    }

    @Operation(description = "Connect user with external reservation")
    @PostMapping("/join")
    fun joinUserToExternalReservation(
        @RequestBody request: UserExtReservationDto,
    ): ResponseEntity<ReservationDto> {
        val externalReservation =
            reservationIntegrationService.getExternalReservation(request.externalSystem, request.externalId)
        val reservationFlowId =
            reservationFlowMutationService.createReservationFlowFromExternalReservation(request, externalReservation)
        val reservation = reservationMutationService.createReservation(reservationFlowId, externalReservation)
        return ResponseEntity.ok(reservation)
    }
}
