package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

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
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val offerRepository: OfferRepository,
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

    /**
     * Preview of payment phases for an offer the customer hasn't committed to
     * yet (i.e. the /enter-your-details screen, before any reservation entity
     * exists). Looks up the offer by (yachtId, dateFrom, dateTo) so we can pull
     * partner-supplied `offer_payment_plan` rows when present and apply them
     * against the customer's actual total — including B4Y agency discount.
     *
     * Falls back to the internal A/B/C rules when no matching offer is found
     * (custom yachts, sync gap, stale dates) so the screen always shows a
     * schedule even if the partner hasn't supplied one.
     */
    @Operation(summary = "Preview payment phases for an unbooked offer (partner-aware when offer exists)")
    @GetMapping("/payment-phases-preview")
    @Transactional(readOnly = true)
    fun previewPaymentPhases(
        @Parameter(description = "Yacht ID") @RequestParam(name = "yachtId", required = true) yachtId: Long,
        @Parameter(description = "Charter start date (YYYY-MM-DD)") @RequestParam(name = "dateFrom", required = true) dateFrom: LocalDate,
        @Parameter(description = "Charter end date (YYYY-MM-DD)") @RequestParam(name = "dateTo", required = true) dateTo: LocalDate,
        @Parameter(description = "Customer-facing total (after B4Y discount, before currency conversion)") @RequestParam(name = "clientTotalPrice", required = true) clientTotalPrice: BigDecimal,
    ): ResponseEntity<List<PaymentPhaseDto>> {
        val offer = offerRepository.findByYachtIdAndDatesWithPaymentPlans(yachtId, dateFrom, dateTo).firstOrNull()

        val phases =
            if (offer != null) {
                paymentPhasesService.calculatePaymentPhases(offer, clientTotalPrice.toDouble())
            } else {
                paymentPhasesService.calculatePaymentPhases(reservationStartDate = dateFrom, totalPrice = clientTotalPrice)
            }

        return ResponseEntity.ok(
            phases.map {
                PaymentPhaseDto(
                    deadline = it.first,
                    amount = it.second.toBigDecimal(),
                )
            },
        )
    }
}
