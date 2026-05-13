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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrElse

@Service
class ReservationMutationService(
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val reservationMappers: ReservationMappers,
    private val offerMutationService: OfferMutationService,
    private val bookingNumberService: BookingNumberService,
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
        /**
         * Admin override — when the admin manually creates a reservation with a
         * bespoke price (replacement after a cancel), the offer's catalogue
         * price is NOT the source of truth. We apply the override to every
         * price field on the Reservation so downstream (customer view, Stripe,
         * email templates) reads the admin price. When null, behaviour is
         * unchanged (external partner's price wins).
         */
        adminOverridePrice: java.math.BigDecimal? = null,
        /**
         * Copied onto the Reservation for admin-only reference (not shown to
         * customer). See `MyReservationDetailsDto` which deliberately omits it.
         */
        adminNotes: String? = null,
    ): ReservationDto {
        val reservationFlow = reservationFlowRepository.findById(reservationFlowId).get()

        // Assign the customer-facing booking number at reservation creation,
        // regardless of status (OPTION or RESERVATION). The number needs to be
        // visible during the payment flow (bank transfer reference, Stripe
        // description, option emails), not only after payment confirmation.
        // Charter year = year the charter actually starts (not booking year),
        // so a reservation booked in Dec 2025 for a May 2026 charter gets
        // "…/2026".
        val reservationNumber = bookingNumberService.next(externalReservation.dateFrom.year)

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
                // Price fields: admin override beats partner response. We apply
                // it to basePrice/totalPrice/clientPrice uniformly so any view
                // reading ANY of those three sees the admin-set value.
                // agencyPrice + commission are left as partner-provided — the
                // admin doesn't override the broker margin, just the customer
                // price. Discount is zeroed on override (no "discount off" a
                // price the admin fully dictated).
                this.basePrice = adminOverridePrice ?: externalReservation.basePrice
                this.discount = if (adminOverridePrice != null) null else externalReservation.discount
                this.commission = externalReservation.commission
                this.totalPrice = adminOverridePrice ?: externalReservation.totalPrice
                this.clientPrice = adminOverridePrice ?: externalReservation.clientPrice
                this.agencyPrice = externalReservation.agencyPrice
                this.deposit = externalReservation.deposit
                this.currency = externalReservation.currency
                this.paymentNote = externalReservation.paymentNote
                this.bankDetails = externalReservation.bankDetails
                this.note = externalReservation.note
                this.locationFrom = externalReservation.locationFrom
                this.locationTo = externalReservation.locationTo
                this.product = externalReservation.product
                this.reservationNumber = reservationNumber
                this.adminNotes = adminNotes?.takeIf { it.isNotBlank() }
            }

        // External payment plan only applies to partner-managed bookings. In
        // the admin-override path, the payment plan lives in
        // `reservation_flow.paymentPhases` (admin-laid-out phases), not in the
        // partner's `externalReservation.paymentPlan`. Skip the copy when the
        // admin is dictating price.
        if (adminOverridePrice == null) {
            createPaymentPlans(reservation, externalReservation)
        }
        createReservationExtras(reservation, externalReservation)

        // Admin-created bookings are never "options awaiting customer payment"
        // — the admin is the authority here, the customer has verbally or
        // otherwise committed, and the admin creates the booking on their
        // behalf. Flip straight to RESERVATION regardless of the paid state
        // of individual installments. The partner returns an OPTION envelope
        // but our internal status is RESERVATION from the start; the
        // externalStatus label is rewritten to match (e.g.
        // "DEV-MOCK-OPTION" → "DEV-MOCK-RESERVATION") so the admin list chip
        // agrees with the detail view.
        //
        // Customer-flow (non-override) bookings keep the OPTION / OPTION_WAITING
        // lifecycle they already had — they get promoted to RESERVATION via
        // `confirmReservation` once the first payment arrives.
        val finalOfferStatus = if (adminOverridePrice != null) {
            reservation.sysStatus = ReservationStatus.RESERVATION
            reservation.externalStatus = reservation.externalStatus?.replace("OPTION", "RESERVATION")
                ?: reservation.externalStatus
            OfferStatus.RESERVED
        } else {
            OfferStatus.OPTION
        }

        reservationRepository.save(reservation)

        offerMutationService.updateOfferStatus(reservationFlow.offer!!.id!!, finalOfferStatus)

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
        // Booking number was already assigned at createReservation; only fill
        // in here for the rare case a reservation entered directly in the
        // RESERVATION state somehow missed it (defensive, shouldn't trigger).
        if (reservation.reservationNumber == null) {
            reservation.reservationNumber = bookingNumberService.next(reservation.dateFrom!!.year)
        }
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

    /**
     * Admin-only manual confirm. Agent calls the agency by phone / email,
     * the agency confirms externally, and the admin records that confirmation
     * here. We DO NOT call the partner (Nausys/MMK) `createBooking` —
     * sandbox credentials refuse it and per business policy the broker drives
     * the confirmation conversation out-of-band.
     *
     * Kept separate from [confirmReservation] so the partner-driven path is
     * still available (e.g. if we later re-enable it for agencies that opted
     * in). This path only mutates our DB:
     *   - sysStatus → RESERVATION
     *   - externalStatus tag → "RESERVATION" (for consistency in list queries)
     *   - offer → RESERVED
     *   - marked payment phases → paidOn = now
     *   - reservation.externalId stays intact, so sync jobs (yacht swap, etc.)
     *     continue to pull partner-side changes for this booking.
     */
    @Transactional
    fun confirmReservationManually(
        reservationId: Long,
        paymentPhaseIds: List<Long>? = null,
    ): ReservationDto {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()

        reservation.sysStatus = ReservationStatus.RESERVATION
        reservation.externalStatus = "RESERVATION"
        if (reservation.reservationNumber == null) {
            reservation.reservationNumber = bookingNumberService.next(reservation.dateFrom!!.year)
        }

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

    /**
     * F1-056: stamp the cancellation-audit fields BEFORE the partner
     * call. Runs in its own REQUIRES_NEW transaction so it commits
     * independently of whatever happens next — even if the partner
     * cancelOption fails or the post-partner [cancelReservation] step
     * never gets to commit, `reservationFlow.cancelationRequestAt`
     * survives as evidence that a cancellation was attempted at this
     * moment by an admin.
     *
     * The same `[AGENT]` prefix that the post-partner step used to
     * apply is applied here instead — admin's reason (if any) wins,
     * customer-side reason (if pre-existing) is preserved as a
     * fallback. Idempotent: re-running on an already-prefixed row
     * does NOT double-prefix because the guard checks for `[AGENT]`
     * before adding it.
     *
     * The cancelation_request column is the existing column for this
     * purpose — no schema migration needed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markCancellationInitiated(
        reservationId: Long,
        adminReason: String? = null,
    ) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val reservationFlow = reservation.reservationFlow!!

        val existingReason = reservationFlow.cancelationRequest?.trim().orEmpty()
        if (!existingReason.startsWith("[AGENT]")) {
            val resolvedReason = adminReason?.trim().orEmpty().ifBlank {
                existingReason.removePrefix("[AGENT]").trim()
            }
            reservationFlow.cancelationRequest = if (resolvedReason.isNotBlank()) {
                "[AGENT] $resolvedReason"
            } else {
                "[AGENT]"
            }
        }
        if (reservationFlow.cancelationRequestAt == null) {
            reservationFlow.cancelationRequestAt = LocalDateTime.now()
        }
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

        // F1-056: cancellation-audit fields (cancelationRequest /
        // cancelationRequestAt) are stamped by
        // [markCancellationInitiated] BEFORE the partner call, so a
        // partner-success / local-fail drift still leaves a visible
        // "[AGENT] cancellation initiated at T" trail on
        // reservation_flow even if this method never reaches commit.

        return reservationMappers.toReservationDto(reservation)
    }

    @Transactional
    fun updateAdminNotes(
        id: Long,
        notes: String?,
    ): ReservationDto {
        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        reservation.adminNotes = notes?.takeIf { it.isNotBlank() }
        return reservationMappers.toReservationDto(reservationRepository.save(reservation))
    }

    @Transactional
    fun updateCrewListUrl(
        id: Long,
        crewListUrl: String?,
    ): ReservationDto {
        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        reservation.crewListUrl = crewListUrl?.takeIf { it.isNotBlank() }
        return reservationMappers.toReservationDto(reservationRepository.save(reservation))
    }

    // Legacy generator replaced by BookingNumberService (per-charter-year
    // counter, format "1001{sequence}/{charter_year}"). Old format was
    // "{sequential prefix starting at 1001}/{current calendar year}".

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

    /**
     * Admin-only path for "we couldn't approve this cancellation" — the
     * customer asked to cancel via /my-bookings but the partner agency
     * refused (their policy doesn't allow it, or partner status doesn't
     * support cancellation any more). Stamps the rejection metadata onto
     * the flow without touching `Reservation.sysStatus` — the booking
     * stays active. The original `cancelationRequest` + `cancelationRequestAt`
     * are preserved as immutable history.
     */
    @Transactional
    fun rejectCancellationRequest(reservationId: Long, reason: String): Reservation {
        val reservation = reservationRepository.findById(reservationId).orElseThrow {
            IllegalArgumentException("Reservation $reservationId not found")
        }
        val flow = reservation.reservationFlow
            ?: throw IllegalStateException("Reservation $reservationId has no flow")

        require(!flow.cancelationRequest.isNullOrBlank()) {
            "Reservation $reservationId has no pending cancellation request to reject"
        }
        require(flow.cancelationRejectedAt == null) {
            "Reservation $reservationId already has a rejected cancellation request"
        }
        require(reason.isNotBlank()) { "Rejection reason is required" }

        flow.cancelationRejectedAt = LocalDateTime.now()
        flow.cancelationRejectedReason = reason.trim().take(2000)
        // Reservation status stays as-is — booking remains active.
        return reservation
    }
}
