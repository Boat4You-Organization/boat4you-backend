package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.KlarnaBilling
import hr.workspace.boat4you.domains.payment.mollie.MollieKlarnaProperties
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.BookingCheckoutMethod
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.CardChargeCalculator
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

/** A PENDING ledger row that a new checkout cannot reuse; the caller asks Mollie about it outside the transaction. */
data class StaleOrder(
    val ledgerId: Long,
    val orderId: String,
)

/**
 * What the create transaction decided (plan 2A.10 M2): an OUTCOME, never an exception after anything was recorded.
 * The non-transactional caller turns it into Mollie calls, a DTO or a 409.
 */
sealed interface CreateOutcome {
    /** A PENDING payment for exactly this charge that stays open long enough: hand out its checkout again. */
    data class Reuse(
        val orderId: String,
        val checkoutUrl: String,
        /** M1: Stripe Checkout sessions of the target phases to expire (outside the transaction) before answering. */
        val stripeSessionIds: List<String> = emptyList(),
    ) : CreateOutcome

    /** PENDING payments of the reservation flow for another charge (or about to expire): clean up first. */
    data class StalePending(
        val orders: List<StaleOrder>,
    ) : CreateOutcome

    /** A target phase was paid by someone else (Stripe webhook, admin, another payment) while we waited for its lock. */
    data object PaidMeanwhile : CreateOutcome

    /** The charge is above the Mollie card limit: no card checkout, the web offers bank transfer only. */
    data object AboveCardLimit : CreateOutcome

    /** The reservation is cancelled: no card checkout at all. */
    data object ReservationClosed : CreateOutcome

    /** Nothing to reuse or clean up: create this payment. */
    data class Create(
        val plan: BookingOrderPlan,
        val stripeSessionIds: List<String> = emptyList(),
    ) : CreateOutcome
}

/**
 * The TRANSACTIONAL part of a Mollie booking checkout: reservation (404) -> phase selection and amount exactly as
 * Stripe (CardChargeCalculator, 400s) -> cancelled reservation -> Mollie card limit -> row locks on the target phases
 * -> reuse before create -> stale PENDING payments -> the plan of a new payment. No Mollie or Stripe call happens in
 * here: the Stripe sessions of the target phases (M1) travel in the outcome and the caller expires them afterwards.
 */
@Service
class MollieBookingCreateTx(
    private val reservationRepository: ReservationRepository,
    private val phaseRepository: ReservationPaymentPhaseRepository,
    private val orders: ReservationCardOrderRepository,
    private val settingsService: AdminSettingsService,
    private val mollieProperties: MollieProperties,
    private val bookingProperties: MollieBookingProperties,
    private val klarnaProperties: MollieKlarnaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun prepare(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        klarnaRequest: KlarnaBillingRequest? = null,
    ): CreateOutcome {
        orders.setLocalLockTimeout()
        val reservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = reservation.reservationFlow ?: throw ReservationNotExistException()
        val reservationFlowId = reservationFlow.id ?: throw ReservationNotExistException()
        if (reservation.sysStatus == ReservationStatus.CANCELLED) return CreateOutcome.ReservationClosed
        // One checkout per reservation flow at a time — also when the charge has no phase rows to lock (legacy pay-full).
        orders.lockKey(ReservationCardOrderTx.flowLockKey(reservationFlowId))

        // Klarna: the billing data of the form, with the booking's own e-mail when the form sent none.
        val klarna = klarnaRequest?.toBilling(reservationFlow.email)
        val method = if (klarna != null) BookingCheckoutMethod.KLARNA else BookingCheckoutMethod.CARD
        val cardSurcharge = CardChargeCalculator.surchargePercentage(settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value)
        val charge =
            CardChargeCalculator.calculate(
                reservationFlow,
                payFullAmount,
                paymentPhaseId,
                if (klarna != null) klarnaProperties.surchargePercentOverride ?: cardSurcharge else cardSurcharge,
            )
        val amountMinor = charge.totalMinor
        if (klarna != null) {
            // 422 (8107): Klarna off, billing country not served, or above that country's limit — nothing is recorded.
            klarnaProperties.requireAvailable(klarna, amountMinor)
        } else if (amountMinor > mollieProperties.cardMaxAmountMinor) {
            log.info("Mollie booking checkout for reservation {}: {} cents is above the card limit -> bank transfer only", reservationId, amountMinor)
            return CreateOutcome.AboveCardLimit
        }

        val targetIds = charge.phases.mapNotNull { it.id }
        if (targetIds.isNotEmpty()) {
            phaseRepository.findAllByIdIn(targetIds)
            // The selection read the phases before the locks: a concurrent payment (Stripe webhook, admin "mark as
            // paid") that committed meanwhile is only visible to a fresh query.
            if (phaseRepository.countByIdInAndPaidOnIsNotNull(targetIds) > 0) {
                log.info("Mollie booking checkout for reservation {}: a target phase of {} was paid meanwhile", reservationId, targetIds)
                return CreateOutcome.PaidMeanwhile
            }
        }

        // M1: a Stripe Checkout session still open on a target phase must not stay payable next to the Mollie payment;
        // expired by the caller OUTSIDE this transaction (a slow Stripe call must not hold the phase locks).
        // stripeSessionId is kept (history).
        val stripeSessionIds = charge.phases.mapNotNull { it.stripeSessionId }.distinct()

        val phaseIds = ReservationCardOrder.phaseIdsOf(targetIds)
        // A double click sends the same web key twice before any row exists (attempt 0 both times -> the same Mollie
        // request, replayed by its Idempotency-Key); once a row with the key exists, the next create is a NEW attempt.
        val webKey = idempotencyKey?.trim()?.take(MAX_IDEMPOTENCY_KEY_LENGTH)?.ifEmpty { null }
        val attempt = webKey?.let { orders.countByReservationFlowIdAndIdempotencyKey(reservationFlowId, it) } ?: 0L
        val billingKey = klarna?.let { billingFingerprint(it) }
        orders
            .findReusable(reservationFlowId, phaseIds, amountMinor, ReservationCardOrderTx.paymentPhaseKey(paymentPhaseId), ReservationCardOrderTx.reusableUntil(), method.name)
            // A Klarna checkout carries the billing address (not stored here): it is handed out again only to the very
            // same request (same web key, same address = the token of the previous attempt); anything else is stale.
            ?.takeIf { klarna == null || (attempt > 0 && it.checkoutToken == checkoutToken(reservationFlowId, phaseIds, amountMinor, webKey, attempt - 1, billingKey)) }
            ?.let { return CreateOutcome.Reuse(it.providerOrderId, it.checkoutUrl, stripeSessionIds) }

        val pending = orders.findPendingByReservationFlow(reservationFlowId)
        if (pending.isNotEmpty()) return CreateOutcome.StalePending(pending.map { StaleOrder(it.id!!, it.providerOrderId) })

        val ttl = bookingProperties.checkoutTtl
        return CreateOutcome.Create(
            BookingOrderPlan(
                reservationId = reservationId,
                reservationFlowId = reservationFlowId,
                reference = reference(reservation.reservationNumber, reservationId),
                phaseIds = phaseIds,
                payFullAmount = payFullAmount,
                paymentPhaseId = paymentPhaseId,
                baseAmount = charge.baseAmount,
                surchargeAmount = charge.surchargeAmount,
                amountMinor = amountMinor,
                idempotencyKey = webKey,
                checkoutToken = checkoutToken(reservationFlowId, phaseIds, amountMinor, webKey, attempt, billingKey),
                attempt = attempt,
                expiresAfter = ttl,
                expiresAt = Instant.now().plus(ttl),
                method = method,
                klarna = klarna,
            ),
            stripeSessionIds,
        )
    }

    companion object {
        /**
         * The checkout token, part of the Mollie request body (redirect URL, metadata). With the web's idempotency key it
         * is derived from (flow, phases, cents, key, attempt), so a repeated create of the same attempt sends the SAME
         * body under the same Idempotency-Key and Mollie returns the same payment; without a key a random one.
         */
        fun checkoutToken(
            reservationFlowId: Long,
            phaseIds: String,
            amountMinor: Long,
            webKey: String?,
            attempt: Long,
            /** [billingFingerprint] of a Klarna checkout; null for a card checkout (the token is then as before Klarna). */
            billingKey: String? = null,
        ): java.util.UUID {
            if (webKey == null) return java.util.UUID.randomUUID()
            val klarnaPart = billingKey?.let { "|klarna|$it" }.orEmpty()
            return java.util.UUID.nameUUIDFromBytes("booking|$reservationFlowId|$phaseIds|$amountMinor|$webKey|$attempt$klarnaPart".toByteArray(Charsets.UTF_8))
        }

        /** SHA-256 of the billing data: a changed address is a different Mollie request, hence a different token. */
        fun billingFingerprint(billing: KlarnaBilling): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    listOf(billing.givenName, billing.familyName, billing.email, billing.streetAndNumber, billing.postalCode, billing.city, billing.country)
                        .joinToString("\u0001")
                        .toByteArray(Charsets.UTF_8),
                ).joinToString("") { "%02x".format(it) }

        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        private const val MAX_REFERENCE_LENGTH = 64
        private val UNSAFE_REFERENCE_CHARS = Regex("[^A-Za-z0-9/_-]")

        /**
         * The public payment reference, as Stripe's (StripePaymentService): the reservation number, else the id,
         * reduced to letters, digits, `/`, `_` and `-` so admin-entered junk never reaches a card statement.
         */
        fun reference(
            reservationNumber: String?,
            reservationId: Long,
        ): String =
            (reservationNumber ?: reservationId.toString())
                .replace(UNSAFE_REFERENCE_CHARS, "")
                .take(MAX_REFERENCE_LENGTH)
                .ifEmpty { reservationId.toString() }
    }
}
