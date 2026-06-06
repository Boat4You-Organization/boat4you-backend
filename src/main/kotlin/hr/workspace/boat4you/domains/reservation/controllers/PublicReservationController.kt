package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.BookingCreationException
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
    private val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

    @Operation(description = "Create a new reservation as a guest (no authentication required).")
    @PostMapping
    fun createGuestReservation(
        @RequestBody @Valid createReservationDto: CreateReservationDto,
    ): ResponseEntity<ReservationDto> {
        // Step 1 — OUR DB flow (+ payment phases, guest user, invite email).
        // Own @Transactional; COMMITS before we return. It also flips the offer
        // FREE -> OPTION under the pessimistic lock (B2 double-submit guard).
        val reservationFlowId = reservationFlowMutationService.createReservationFlow(createReservationDto)

        // Steps 2-4 happen AFTER step 1 already committed, so any failure here
        // would otherwise leave an orphan live IN_PROGRESS flow + the offer
        // parked in OPTION (B2 defect 1). Wrap + compensate.
        return runBooking(reservationFlowId)
    }

    /**
     * B2 orchestration: partner createOption -> our reservation -> email.
     * On ANY failure, compensate (abandon the flow + revert the offer, plus a
     * best-effort partner option release if one was already created) and rethrow
     * a clean exception instead of leaving an orphan or surfacing a raw 500.
     * A committed reservation is NEVER turned into a 500 by an unexpected
     * (non-OPTION) partner status.
     */
    private fun runBooking(reservationFlowId: Long): ResponseEntity<ReservationDto> {
        var reservation: ReservationDto? = null
        // Hoisted so the catch can release the partner option even when step 3
        // (createReservation) throws AFTER step 2 created it — no reservation row
        // exists in that window, so deleteExternalReservation(id) can't reach it.
        var partnerOption: hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper? = null
        try {
            val externalReservation =
                reservationIntegrationService.createExternalReservation(reservationFlowId)
            partnerOption = externalReservation

            reservation = reservationMutationService.createReservation(reservationFlowId, externalReservation)

            // From here on the reservation is COMMITTED. Email dispatch is
            // best-effort: a mail/template glitch must NOT drop into the catch
            // below and tear down a good booking. The status is informational —
            // we never 500 on a committed reservation (B2 defect 2).
            val committed = reservation
            runCatching {
                when (externalReservation.calculatedSysStatus) {
                    ReservationStatus.OPTION, ReservationStatus.OPTION_WAITING ->
                        reservationEmailService.sendOptionCreatedEmail(committed.id)
                    ReservationStatus.RESERVATION ->
                        // Partner auto-confirmed straight to RESERVATION — this is
                        // a SUCCESS, not a failure. Send the confirmation email
                        // instead of the option email.
                        reservationEmailService.sendConfirmationForReserved(committed, PaymentType.BANK_TRANSFER)
                    else ->
                        // CANCELLED / UNKNOWN on a freshly-created option: keep
                        // the committed reservation, return 200, no email fits;
                        // an admin reconciles via the booking list.
                        log.warn(
                            "Booking {} created with unexpected partner status {} — kept, no email sent.",
                            committed.id,
                            externalReservation.calculatedSysStatus,
                        )
                }
            }.onFailure { log.error("Booking {} committed but post-create email failed", committed.id, it) }

            return ResponseEntity.ok(reservation)
        } catch (e: Exception) {
            // Best-effort: release a partner option if one was actually created
            // (reservation committed with an external_id). If step 2 threw,
            // no reservation/external option exists yet — nothing to release.
            // Release the partner option whichever way it was created:
            //  - reservation committed (step 3 ok) → cancel by reservation id
            //  - step 3 threw AFTER step 2 created the option → cancel straight
            //    from the createOption wrapper (no reservation row exists), else
            //    the partner option leaks until it auto-expires (B2 P2).
            val committedResId = reservation?.id
            if (committedResId != null) {
                runCatching { reservationIntegrationService.deleteExternalReservation(committedResId) }
                    .onFailure { log.warn("Compensation: partner option release failed for reservation {}", committedResId, it) }
            } else {
                partnerOption?.let { wrapper ->
                    runCatching { reservationIntegrationService.cancelExternalOptionByWrapper(reservationFlowId, wrapper) }
                        .onFailure { log.warn("Compensation: partner option release (by wrapper) failed for flow {}", reservationFlowId, it) }
                }
            }
            // Compensate OUR side: revert offer (FREE unless another live
            // reservation holds it) + mark the flow ABANDONED so it is not a
            // live orphan blocking the slot. Best-effort — never mask the
            // original failure.
            runCatching { reservationMutationService.abandonFailedFlow(reservationFlowId) }
                .onFailure { log.error("Compensation: abandonFailedFlow failed for flow {}", reservationFlowId, it) }

            // Already-clean domain exceptions (partner option/reservation error,
            // yacht/agency not active, flow not found, ...) keep their existing
            // 4xx mapping. Anything else (raw IllegalState/NPE from the persist
            // step) becomes a clean BookingCreationException instead of the
            // catch-all 500. These are independent classes (not a hierarchy), so
            // each must be listed explicitly.
            throw when (e) {
                is hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException,
                is hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException,
                is hr.workspace.boat4you.domains.external.exceptions.ExternalReservationException,
                is hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException,
                is hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException,
                is hr.workspace.boat4you.domains.reservation.exceptions.ReservationFlowNotExists,
                -> e
                else -> BookingCreationException("Booking orchestration failed for flow $reservationFlowId", e)
            }
        }
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
