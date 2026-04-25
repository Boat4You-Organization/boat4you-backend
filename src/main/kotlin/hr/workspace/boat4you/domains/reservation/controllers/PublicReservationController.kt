package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public reservation endpoint — accepts anonymous (guest) bookings.
 *
 * Mounted under the /public path (allow-listed in SecurityConfiguration), so no
 * Bearer token is required. The guest is identified by email; the service layer
 * will find-or-create a user entity from that email.
 */
@Tag(name = "Reservations (public)")
@Validated
@RestController
@RequestMapping("/public/reservations")
class PublicReservationController(
    private val reservationFlowMutationService: ReservationFlowMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationEmailService: ReservationEmailService,
) {
    @Operation(description = "Create a new reservation as a guest (no authentication required).")
    @PostMapping
    fun createGuestReservation(
        @RequestBody @Valid createReservationDto: CreateReservationDto,
    ): ResponseEntity<ReservationDto> {
        val reservationFlowId = reservationFlowMutationService.createReservationFlow(createReservationDto)

        val externalReservation =
            reservationIntegrationService.createExternalReservation(reservationFlowId)

        val reservation = reservationMutationService.createReservation(reservationFlowId, externalReservation)

        if (externalReservation.calculatedSysStatus == ReservationStatus.OPTION) {
            reservationEmailService.sendOptionCreatedEmail(reservation.id)
        } else {
            throw IllegalStateException(
                "External reservation status is not OPTION, but ${externalReservation.calculatedSysStatus}",
            )
        }

        return ResponseEntity.ok(reservation)
    }
}
