package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.CardChargeCalculator
import hr.workspace.boat4you.domains.reservation.service.StripeSessionExpirer
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
    ) : CreateOutcome

    /** PENDING payments of the reservation flow for another charge (or about to expire): clean up first. */
    data class StalePending(
        val orders: List<StaleOrder>,
    ) : CreateOutcome

    /** A target phase was paid by someone else (Stripe webhook, admin, another payment) while we waited for its lock. */
    data object PaidMeanwhile : CreateOutcome

    /** The charge is above the Mollie card limit: no card checkout, the web offers bank transfer only. */
    data object AboveCardLimit : CreateOutcome

    /** Nothing to reuse or clean up: create this payment. */
    data class Create(
        val plan: BookingOrderPlan,
    ) : CreateOutcome
}

/**
 * The TRANSACTIONAL part of a Mollie booking checkout: reservation (404) -> phase selection and amount exactly as
 * Stripe (CardChargeCalculator, 400s) -> Mollie card limit -> row locks on the target phases -> M1: expire a still open
 * Stripe Checkout session of those phases -> reuse before create -> stale PENDING payments -> the plan of a new
 * payment. No Mollie call happens in here.
 */
@Service
class MollieBookingCreateTx(
    private val reservationRepository: ReservationRepository,
    private val phaseRepository: ReservationPaymentPhaseRepository,
    private val orders: ReservationCardOrderRepository,
    private val settingsService: AdminSettingsService,
    private val stripeSessionExpirer: StripeSessionExpirer,
    private val mollieProperties: MollieProperties,
    private val bookingProperties: MollieBookingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun prepare(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
    ): CreateOutcome {
        orders.setLocalLockTimeout()
        val reservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = reservation.reservationFlow ?: throw ReservationNotExistException()
        val reservationFlowId = reservationFlow.id ?: throw ReservationNotExistException()
        // One checkout per reservation flow at a time — also when the charge has no phase rows to lock (legacy pay-full).
        orders.lockKey(ReservationCardOrderTx.flowLockKey(reservationFlowId))

        val charge =
            CardChargeCalculator.calculate(
                reservationFlow,
                payFullAmount,
                paymentPhaseId,
                CardChargeCalculator.surchargePercentage(settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value),
            )
        val amountMinor = charge.totalMinor
        if (amountMinor > mollieProperties.cardMaxAmountMinor) {
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

        // M1: a Stripe Checkout session still open on a target phase must not stay payable next to the Mollie payment.
        // stripeSessionId is kept (history).
        charge.phases.forEach { phase -> phase.stripeSessionId?.let { stripeSessionExpirer.expireStripeSessionSafely(it) } }

        val phaseIds = ReservationCardOrder.phaseIdsOf(targetIds)
        orders
            .findReusable(reservationFlowId, phaseIds, amountMinor, ReservationCardOrderTx.paymentPhaseKey(paymentPhaseId), ReservationCardOrderTx.reusableUntil())
            ?.let { return CreateOutcome.Reuse(it.providerOrderId, it.checkoutUrl) }

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
                idempotencyKey = idempotencyKey?.trim()?.take(MAX_IDEMPOTENCY_KEY_LENGTH)?.ifEmpty { null },
                checkoutToken = java.util.UUID.randomUUID(),
                expiresAfter = ttl,
                expiresAt = Instant.now().plus(ttl),
            ),
        )
    }

    companion object {
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
