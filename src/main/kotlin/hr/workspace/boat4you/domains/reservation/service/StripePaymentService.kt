package hr.workspace.boat4you.domains.reservation.service

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.checkout.SessionCreateParams
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.events.ReservationPaymentRecordedEvent
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import hr.workspace.boat4you.domains.voucher.service.VoucherService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

@Service
class StripePaymentService(
    private val reservationRepository: ReservationRepository,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val settingsService: AdminSettingsService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationEmailService: ReservationEmailService,
    private val stripeEventIdempotencyService: StripeEventIdempotencyService,
    private val voucherService: VoucherService,
    private val eventPublisher: ApplicationEventPublisher,
    private val stripeSessionExpirer: StripeSessionExpirer,
    private val doublePaymentAlert: CardDoublePaymentAlert,
    @Value("\${server.host-public}") private val serverHostPublic: String,
    @Value("\${application.stripe.enabled}") private val stripeEnabled: Boolean,
    // 3-letter ISO 4217. Stripe wants lowercase. EUR is the product default;
    // overridable per-env via APPLICATION_STRIPE_CURRENCY when an operator
    // prices in USD/GBP. Per-offer override would need a bigger change
    // (offer + phase currency fields) — that's a separate follow-up.
    @Value("\${application.stripe.currency:eur}") private val stripeCurrency: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Transactional(readOnly = false)
    fun initiatePayment(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String? = null,
    ): Session {
        checkIfStripeIsEnabled()
        val dbReservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = dbReservation.reservationFlow ?: throw ReservationNotExistException()
        // F1-026: extract the reservation-flow id once with an explicit
        // null check so the metadata calls below do not need `!!`. A
        // persisted reservation flow loaded via repository always has
        // an id, but the type is `Long?` on AbstractEntity — making
        // the assumption explicit fails fast with a clear message
        // instead of an NPE somewhere in the Stripe SDK.
        val reservationFlowId = reservationFlow.id
            ?: throw ReservationNotExistException()

        // Phase selection (400s), card surcharge and cents: CardChargeCalculator — moved verbatim from here and
        // shared with the provider-neutral /payments/card checkout, so Stripe and Mollie charge byte-identical
        // amounts (golden values pinned in CardChargeCalculatorTests). Same order as before: the selection's 400s
        // fire before the surcharge setting is read and before any Stripe call.
        val dbPrice: BigDecimal = CardChargeCalculator.select(reservationFlow, payFullAmount, paymentPhaseId).baseAmount
        val cardSurchargePercentage =
            CardChargeCalculator.surchargePercentage(settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value)
        val cardSurchargeAmount = CardChargeCalculator.surcharge(dbPrice, cardSurchargePercentage)
        val totalPriceInCents = CardChargeCalculator.toCentsLong(dbPrice + cardSurchargeAmount)

        // The customer-facing booking number is the public payment reference
        // across all channels (bank transfer instructions, emails, Stripe).
        // Format: "1001{sequence}/{charter_year}", e.g. "100176/2026".
        // Using it as `client_reference_id` makes it the top-level identifier
        // in Stripe Dashboard search; `payment_intent_data.description`
        // pushes it onto the customer's bank card statement. Fallback to
        // reservation id for the (historical) case where no number was set.
        // Sanitize to alphanumeric + slash/dash/underscore so admin-entered
        // junk never lands on a customer's bank statement or in Stripe's
        // dashboard search index.
        val paymentReference = (dbReservation.reservationNumber ?: reservationId.toString())
            .replace(Regex("[^A-Za-z0-9/_-]"), "")

        val params =
            SessionCreateParams
                .builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("$serverHostPublic/payment-success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("$serverHostPublic/payment-cancelled")
                .setClientReferenceId(paymentReference)
                .setPaymentIntentData(
                    SessionCreateParams.PaymentIntentData
                        .builder()
                        .setDescription("Boat4you reservation #$paymentReference")
                        .putMetadata("reservationId", reservationId.toString())
                        .putMetadata("reservationFlowId", reservationFlowId.toString())
                        .build(),
                )
                .putMetadata("reservationId", reservationId.toString())
                .putMetadata("reservationFlowId", reservationFlowId.toString())
                .apply {
                    payFullAmount?.let { putMetadata("payFullAmount", it.toString()) }
                    paymentPhaseId?.let { putMetadata("paymentPhaseId", it.toString()) }
                }.addLineItem(
                    SessionCreateParams.LineItem
                        .builder()
                        .setQuantity(1L)
                        .setPriceData(
                            SessionCreateParams.LineItem.PriceData
                                .builder()
                                .setCurrency(stripeCurrency)
                                .setUnitAmount(totalPriceInCents)
                                .setProductData(
                                    SessionCreateParams.LineItem.PriceData.ProductData
                                        .builder()
                                        .setName("Boat Booking — Reservation #$paymentReference")
                                        .build(),
                                ).build(),
                        ).build(),
                ).build()

        // Stripe idempotency: when the frontend passes a key (generated once
        // per /payment page mount), Stripe dedupes retries for 24 h — a double
        // click returns the same Session instead of charging twice. Missing
        // key falls back to non-idempotent create so older callers still work.
        val session =
            if (idempotencyKey.isNullOrBlank()) {
                Session.create(params)
            } else {
                Session.create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build())
            }
        setSessionIdOnPaymentPhases(payFullAmount, paymentPhaseId, reservationFlow, session.id)

        logger.debug("Created Stripe sessionId ${session.id} for reservationId $reservationId")
        return session
    }

    @Transactional(readOnly = false)
    fun handleWebhookEvent(event: Event) {
        checkIfStripeIsEnabled()

        // F3-022: event-level idempotency. Stripe webhook delivery is
        // at-least-once with automatic retries (~3 day window). Without
        // this claim, every redelivery of `checkout.session.completed`
        // re-fires the full promoteReservationToBooking chain (partner
        // confirm + DB save + customer email), causing duplicate partner
        // bookings and duplicate customer emails. The claim commits in
        // REQUIRES_NEW so it survives a rollback of the outer handler —
        // subsequent retries become no-ops at the cost of needing manual
        // reconciliation for partial failures (V1_91 migration comment).
        //
        // F3-024 note: the partner confirm → DB save → email chain
        // inside promoteReservationToBooking is not atomic across the
        // external partner call. A successful partner confirm followed
        // by a local rollback would normally leave the partner with a
        // booking we do not know about; the idempotency claim above
        // turns that into a manual-reconciliation case instead of a
        // duplicate-booking case on Stripe retry.
        if (!stripeEventIdempotencyService.claimEventIfNew(event.id, event.type)) {
            logger.info("Stripe event ${event.id} (${event.type}) already processed — skipping")
            return
        }

        // F3-025: webhook payloads are external input, so defensively
        // null-check every step instead of `!!`-asserting. Any malformed
        // event is logged at error and skipped — the claim above means
        // Stripe will not retry it, so an operator alert is the right
        // outcome rather than a crash loop.
        val session = event.dataObjectDeserializer.`object`.orElse(null) as? Session
        if (session == null) {
            logger.error("Stripe event ${event.id} (${event.type}) has no Session payload — skipping")
            return
        }

        if (event.type != "checkout.session.completed") {
            logger.error(
                "Stripe event ${event.id} (${event.type}) for sessionId ${session.id} is not a completion event — skipping",
            )
            return
        }

        logger.debug("Payment for Stripe sessionId ${session.id} successful")

        val paymentIntentId = session.paymentIntent
        val metadata = session.metadata ?: emptyMap()

        val reservationId = metadata["reservationId"]?.toLongOrNull()
        if (reservationId == null) {
            logger.error(
                "Stripe event ${event.id}: session ${session.id} missing or invalid reservationId metadata",
            )
            return
        }
        val payFullAmount = metadata["payFullAmount"]?.toBoolean()
        val paymentPhaseId = metadata["paymentPhaseId"]?.toLongOrNull()

        val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(session.id)

        // A phase of this session was paid meanwhile by something else (a Mollie card payment, the office marking a
        // bank transfer): this Stripe payment is a SECOND payment. Never overwrite the phase or confirm the booking
        // again — the office gets an alert and refunds one of the two.
        val stripeTargets =
            when {
                payFullAmount == true -> dbPaymentPhases
                payFullAmount == false -> listOfNotNull(dbPaymentPhases.firstOrNull())
                paymentPhaseId != null -> dbPaymentPhases.filter { it.id == paymentPhaseId }
                else -> emptyList()
            }
        val alreadyPaid = stripeTargets.filter { it.paidOn != null }
        if (alreadyPaid.isNotEmpty()) {
            logger.error(
                "Stripe event ${event.id}: session ${session.id} paid, but phase(s) ${alreadyPaid.map { it.id }} of reservation " +
                    "$reservationId were already paid — DOUBLE PAYMENT, nothing changed",
            )
            doublePaymentAlert.stripeAfterAnotherPayment(reservationId, session.id, paymentIntentId, alreadyPaid.mapNotNull { it.id })
            return
        }

        when {
            // payFullAmount=true tolerates an empty list — the legacy
            // "first payment, no installment plan rows yet" flow runs
            // through here with zero matched phases, and the booking
            // promotion below is what completes the reservation.
            payFullAmount == true -> dbPaymentPhases.forEach(setPaymentMetadata(paymentIntentId))
            payFullAmount == false -> {
                val phase = dbPaymentPhases.firstOrNull()
                if (phase == null) {
                    logger.error(
                        "Stripe event ${event.id}: payFullAmount=false but no phases match sessionId ${session.id}",
                    )
                    return
                }
                phase.apply(setPaymentMetadata(paymentIntentId))
            }
            paymentPhaseId != null -> {
                val phase = dbPaymentPhases.find { it.id == paymentPhaseId }
                if (phase == null) {
                    logger.error(
                        "Stripe event ${event.id}: paymentPhaseId=$paymentPhaseId not present among phases for sessionId ${session.id}",
                    )
                    return
                }
                phase.apply(setPaymentMetadata(paymentIntentId))
            }
            else -> {
                logger.error(
                    "Stripe event ${event.id}: session ${session.id} has neither payFullAmount nor paymentPhaseId metadata",
                )
                return
            }
        }

        // Reservation is created for the first time
        if (paymentPhaseId == null) {
            promoteReservationToBooking(reservationId)
        }

        // Follow-ups (booking review request) run after this transaction commits and cannot fail the payment.
        eventPublisher.publishEvent(ReservationPaymentRecordedEvent(reservationId))
    }

    @Transactional(readOnly = true)
    fun checkPaymentStatus(stripeSessionId: String): CheckoutSessionDto {
        checkIfStripeIsEnabled()
        val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(stripeSessionId)
        val paymentStatus =
            if (dbPaymentPhases.any { it.paidOn == null }) {
                CheckoutSessionStatusEnum.PAYMENT_PENDING
            } else {
                CheckoutSessionStatusEnum.PAYMENT_SUCCESS
            }

        return CheckoutSessionDto(sessionIdOrOrderCode = stripeSessionId, status = paymentStatus)
    }

    private fun setSessionIdOnPaymentPhases(
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        reservationFlow: ReservationFlow,
        sessionId: String,
    ) {
        // F3-026: every branch must select among UNPAID phases. The
        // previous `oldest()` returned the historically-earliest phase
        // regardless of paidOn — on admin-replacement reservations that
        // carry over a paid first installment, that meant stamping the
        // new sessionId onto an already-paid phase, and the webhook
        // would later overwrite its paidOn/stripePaymentIntentId on
        // completion (and the customer would have paid the wrong row).
        val targets: List<ReservationPaymentPhase> =
            when {
                payFullAmount == true -> reservationFlow.paymentPhases.filter { it.paidOn == null }
                payFullAmount == false -> listOfNotNull(reservationFlow.paymentPhases.oldestUnpaid())
                paymentPhaseId != null -> {
                    val phase = reservationFlow.paymentPhases.find { it.id == paymentPhaseId }
                        ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase not found"))
                    if (phase.paidOn != null) {
                        throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase is already paid"))
                    }
                    listOf(phase)
                }
                else -> emptyList()
            }

        targets.forEach { phase ->
            // F3-023: if the phase already has a live Stripe session
            // (user reopened the payment page → fresh Session.create on
            // this call), expire the old one before overwriting so the
            // funds cannot be captured against both sessions in the gap
            // between issuing the new one and the user paying.
            phase.stripeSessionId?.let { stripeSessionExpirer.expireStripeSessionSafely(it) }
            phase.stripeSessionId = sessionId
        }
    }

    private fun setPaymentMetadata(paymentIntentId: String): (ReservationPaymentPhase) -> Unit =
        {
            it.paidOn = Instant.now()
            it.stripePaymentIntentId = paymentIntentId
        }

    private fun promoteReservationToBooking(reservationId: Long) {
        val externalReservation = reservationIntegrationService.confirmExternalReservation(reservationId)
        val reservationResponse = reservationMutationService.confirmReservation(reservationId, externalReservation)

        reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.CARD)
        runCatching { voucherService.issueForConfirmedReservation(reservationId) }
            .onFailure { logger.error("Voucher issuance failed for reservation $reservationId", it) }
    }

    private fun checkIfStripeIsEnabled() {
        if (!stripeEnabled) {
            throw RuntimeException("Stripe is not enabled in this environment")
        }
    }

    private fun Set<ReservationPaymentPhase>.oldestUnpaid(): ReservationPaymentPhase? =
        this.filter { it.paidOn == null }.minByOrNull { it.deadline }
}
