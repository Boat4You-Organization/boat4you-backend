package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.payment.revolut.RevolutCaptureMode
import hr.workspace.boat4you.domains.payment.revolut.RevolutCreateOrderRequest
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantClient
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderStates
import hr.workspace.boat4you.domains.payment.revolut.RevolutProperties
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.service.payment.CardAlreadyPaidException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentInProgressException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProvider
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import hr.workspace.boat4you.domains.reservation.service.payment.CardProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import java.net.URI

/**
 * Revolut hosted checkout for ONLINE-BOOKING card payments (Revolut plan 2A.4; provider swap only — the phases and the
 * amount come from CardChargeCalculator exactly as for Stripe).
 *
 * No transaction here on purpose: every Revolut HTTP call happens OUTSIDE a database transaction. The database work
 * is [RevolutBookingCreateTx.prepare] (one transaction that returns an outcome — M2) and the short transactions of
 * [ReservationCardOrderTx]. A checkout:
 * 1. `prepare`: reservation (404), selection (400), phase locks, M1 (expire an open Stripe session), then REUSE a
 *    still open PENDING order for the same charge (no Revolut call), or STALE PENDING orders of the flow to clean up,
 *    or the plan of a new order (CREATE);
 * 2. stale orders: read each from Revolut (one attempt): `pending` -> cancel + ledger CANCELLED, then `prepare` once
 *    more; `processing|authorised` -> 409 CARD_PAYMENT_IN_PROGRESS; `completed` -> [BookingOrderCompletion] in its own
 *    transaction, then 409 CARD_ALREADY_PAID; `failed|cancelled` -> ledger closed;
 * 3. create the order (never retried; error -> 502, no ledger row), check the checkout origin, insert the PENDING
 *    ledger row (a concurrent identical checkout that won the race is returned instead and our order is cancelled).
 */
@Service
class RevolutBookingPaymentService(
    private val properties: RevolutProperties,
    private val bookingProperties: RevolutBookingProperties,
    private val client: RevolutMerchantClient,
    private val createTx: RevolutBookingCreateTx,
    private val orderTx: ReservationCardOrderTx,
    private val orders: ReservationCardOrderRepository,
    private val completion: BookingOrderCompletion,
    @Value("\${server.host-public}") serverHostPublic: String,
) : CardPaymentProvider {
    override val provider: CardProvider = CardProvider.REVOLUT

    private val log = LoggerFactory.getLogger(javaClass)
    private val hostPublic = serverHostPublic.trim().trimEnd('/')

    override fun createCheckout(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        locale: String?,
    ): CheckoutSessionDto {
        if (!properties.enabled) throw CardPaymentsUnavailableException()
        var outcome = prepare(reservationId, payFullAmount, paymentPhaseId, idempotencyKey)
        if (outcome is CreateOutcome.StalePending) {
            resolveStale(reservationId, outcome.orders)
            outcome = prepare(reservationId, payFullAmount, paymentPhaseId, idempotencyKey)
        }
        return when (outcome) {
            is CreateOutcome.Reuse -> {
                log.info("Revolut booking checkout for reservation {}: reusing order {}", reservationId, outcome.orderId)
                CheckoutSessionDto(outcome.orderId, CheckoutSessionStatusEnum.PAYMENT_PENDING, outcome.checkoutUrl)
            }
            is CreateOutcome.Create -> create(outcome.plan, locale)
            CreateOutcome.PaidMeanwhile -> throw CardAlreadyPaidException()
            is CreateOutcome.StalePending -> {
                // Cleaned up once already; another order appeared meanwhile (a second tab) — do not loop.
                log.warn("Revolut booking checkout for reservation {}: pending orders again after the cleanup -> 409", reservationId)
                throw CardPaymentInProgressException()
            }
        }
    }

    /**
     * Status of a checkout this provider created. Unknown (or not an order id) -> null: the router asks Stripe, then
     * answers 404. A PENDING row asks Revolut at most once per 10 s (claimCheck), only while the Merchant API is
     * configured; a Revolut hiccup is swallowed and the stored status is returned. COMPLETED -> PAYMENT_SUCCESS,
     * anything else -> PAYMENT_PENDING.
     */
    override fun checkStatus(ref: String): CheckoutSessionDto? {
        if (!RevolutMerchantClient.isValidOrderId(ref)) return null
        var row = orders.findByProviderOrderId(ref) ?: return null
        val id = row.id!!
        if (row.status == ReservationCardOrderStatus.PENDING && properties.enabled && orders.claimCheck(id) == 1) {
            val order =
                try {
                    client.getOrderOnce(ref)
                } catch (e: Exception) {
                    log.warn("Revolut booking order {}: status check could not read the order ({})", ref, e.javaClass.simpleName)
                    null
                }
            if (order != null) {
                runCatching { applyState(id, order) }
                    .onFailure { log.error("Revolut booking order {}: status check could not apply state {}", ref, order.state, it) }
                row = orders.findById(id).orElse(row)
            }
        }
        val status =
            if (row.status == ReservationCardOrderStatus.COMPLETED) {
                CheckoutSessionStatusEnum.PAYMENT_SUCCESS
            } else {
                CheckoutSessionStatusEnum.PAYMENT_PENDING
            }
        return CheckoutSessionDto(sessionIdOrOrderCode = ref, status = status)
    }

    private fun prepare(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
    ): CreateOutcome = lockTimeoutIsInProgress(reservationId) { createTx.prepare(reservationId, payFullAmount, paymentPhaseId, idempotencyKey) }

    /** Step 2 of the class doc. Throws the 409 / 502 the checkout must answer; returns when a new order may be created. */
    private fun resolveStale(
        reservationId: Long,
        stale: List<StaleOrder>,
    ) {
        var completed = false
        var inProgress = false
        stale.forEach { row ->
            val order = readForCheckout(reservationId, row)
            when (order.state.lowercase()) {
                RevolutOrderStates.PENDING -> if (!cancelPending(reservationId, row)) inProgress = true
                RevolutOrderStates.PROCESSING, RevolutOrderStates.AUTHORISED -> {
                    log.info("Revolut booking checkout for reservation {}: order {} is {} -> 409", reservationId, row.orderId, order.state)
                    inProgress = true
                }
                RevolutOrderStates.COMPLETED -> {
                    log.info("Revolut booking checkout for reservation {}: order {} is already completed", reservationId, row.orderId)
                    applyCompleted(row.ledgerId, order)
                    completed = true
                }
                RevolutOrderStates.CANCELLED -> close(reservationId, row, ReservationCardOrderStatus.CANCELLED)
                RevolutOrderStates.FAILED -> close(reservationId, row, ReservationCardOrderStatus.FAILED)
                else -> {
                    log.warn("Revolut booking checkout for reservation {}: order {} in unexpected state {} -> 409", reservationId, row.orderId, order.state)
                    inProgress = true
                }
            }
        }
        // M2: CARD_ALREADY_PAID only here, after the completion committed in its own transaction.
        if (completed) throw CardAlreadyPaidException()
        if (inProgress) throw CardPaymentInProgressException()
    }

    /** One attempt (plan 2A.10 §3): a customer is waiting. Unreadable or unknown to Revolut -> 502, nothing changes. */
    private fun readForCheckout(
        reservationId: Long,
        row: StaleOrder,
    ): RevolutOrder {
        val order =
            try {
                client.getOrderOnce(row.orderId)
            } catch (e: Exception) {
                log.warn("Revolut booking checkout for reservation {}: order {} not readable ({}) -> 502", reservationId, row.orderId, e.javaClass.simpleName)
                throw CardPaymentProviderException()
            }
        if (order == null) {
            log.warn("Revolut booking checkout for reservation {}: Revolut does not know order {} (ledger {}) -> 502", reservationId, row.orderId, row.ledgerId)
            throw CardPaymentProviderException()
        }
        return order
    }

    /** Cancels a still pending order; false when Revolut refused (the payer is probably paying it right now). */
    private fun cancelPending(
        reservationId: Long,
        row: StaleOrder,
    ): Boolean {
        val cancelled =
            try {
                client.cancelOrder(row.orderId)
            } catch (e: Exception) {
                log.warn("Revolut booking checkout for reservation {}: order {} not cancelled ({}) -> 409", reservationId, row.orderId, e.javaClass.simpleName)
                return false
            }
        if (cancelled != null && cancelled.state.lowercase() != RevolutOrderStates.CANCELLED) {
            log.warn("Revolut booking checkout for reservation {}: order {} is {} after cancel -> 409", reservationId, row.orderId, cancelled.state)
            return false
        }
        log.info("Revolut booking checkout for reservation {}: stale pending order {} cancelled", reservationId, row.orderId)
        close(reservationId, row, ReservationCardOrderStatus.CANCELLED)
        return true
    }

    private fun close(
        reservationId: Long,
        row: StaleOrder,
        target: ReservationCardOrderStatus,
    ) {
        lockTimeoutIsInProgress(reservationId) { orderTx.closeIfPending(row.ledgerId, target) }
    }

    /** Step 3 of the class doc. */
    private fun create(
        plan: BookingOrderPlan,
        locale: String?,
    ): CheckoutSessionDto {
        val order =
            try {
                client.createOrder(orderRequest(plan, locale))
            } catch (e: Exception) {
                log.warn("Revolut booking checkout for reservation {}: order not created ({}: {})", plan.reservationId, e.javaClass.simpleName, e.message)
                throw CardPaymentProviderException()
            }
        if (!RevolutMerchantClient.isValidOrderId(order.id)) {
            log.error("Revolut booking checkout for reservation {}: Revolut returned a malformed order id", plan.reservationId)
            throw CardPaymentProviderException()
        }
        val checkoutUrl = order.checkoutUrl
        if (checkoutUrl == null || !isAllowedCheckout(checkoutUrl)) {
            log.error("Revolut booking checkout for reservation {}: order {} has no checkout URL on an allowed origin", plan.reservationId, order.id)
            cancelQuietly(order.id)
            throw CardPaymentProviderException()
        }
        val inserted =
            try {
                lockTimeoutIsInProgress(plan.reservationId) { orderTx.insertPending(plan, order.id, checkoutUrl) }
            } catch (e: Exception) {
                // Nobody has the checkout URL yet: the order must not stay payable without a ledger row.
                cancelQuietly(order.id)
                throw e
            }
        if (inserted.existing) {
            log.info(
                "Revolut booking checkout for reservation {}: a concurrent checkout created order {} first; cancelling {}",
                plan.reservationId,
                inserted.order.providerOrderId,
                order.id,
            )
            cancelQuietly(order.id)
            return CheckoutSessionDto(inserted.order.providerOrderId, CheckoutSessionStatusEnum.PAYMENT_PENDING, inserted.order.checkoutUrl)
        }
        log.info(
            "Revolut booking order {} created for reservation {} (phases [{}], {} cents, expire_pending_after {})",
            order.id,
            plan.reservationId,
            plan.phaseIds,
            plan.amountMinor,
            plan.expiresAfter,
        )
        return CheckoutSessionDto(order.id, CheckoutSessionStatusEnum.PAYMENT_PENDING, checkoutUrl)
    }

    private fun orderRequest(
        plan: BookingOrderPlan,
        locale: String?,
    ) = RevolutCreateOrderRequest(
        amount = plan.amountMinor,
        currency = ReservationCardOrder.CURRENCY_EUR,
        captureMode = RevolutCaptureMode.AUTOMATIC,
        description = "Boat4you reservation #${plan.reference}",
        redirectUrl = "$hostPublic${localePrefix(locale)}/payment-success?reservationId=${plan.reservationId}",
        merchantOrderData = RevolutMerchantOrderData(reference = plan.reference),
        metadata =
            buildMap {
                put("reservationId", plan.reservationId.toString())
                put("reservationFlowId", plan.reservationFlowId.toString())
                plan.payFullAmount?.let { put("payFullAmount", it.toString()) }
                plan.paymentPhaseId?.let { put("paymentPhaseId", it.toString()) }
            },
        expirePendingAfter = plan.expiresAfter.toString(),
        enforceChallenge = bookingProperties.enforceChallenge,
    )

    /** Applies what Revolut says about an order outside the checkout (status endpoint). */
    private fun applyState(
        ledgerId: Long,
        order: RevolutOrder,
    ) {
        when (order.state.lowercase()) {
            RevolutOrderStates.COMPLETED -> applyCompleted(ledgerId, order)
            RevolutOrderStates.CANCELLED -> orderTx.closeIfPending(ledgerId, ReservationCardOrderStatus.CANCELLED)
            RevolutOrderStates.FAILED -> orderTx.closeIfPending(ledgerId, ReservationCardOrderStatus.FAILED)
            else -> Unit
        }
    }

    /**
     * ===== STAGE C HOOK ===== the ONE call site of the completion routine on the checkout / status paths: always
     * outside a transaction, the routine runs in its own ([BookingOrderCompletion]). A failure is logged and never
     * blocks the caller's answer — the reconcile job (stage C) retries.
     */
    private fun applyCompleted(
        ledgerId: Long,
        order: RevolutOrder,
    ) {
        runCatching { completion.applyCompleted(ledgerId, order) }
            .onFailure { log.error("Revolut booking order {} (ledger {}): recording the completion failed", order.id, ledgerId, it) }
    }

    private fun cancelQuietly(orderId: String) {
        runCatching { client.cancelOrder(orderId) }
            .onFailure { log.warn("Revolut booking order {}: cancel failed ({}); it expires on its own", orderId, it.javaClass.simpleName) }
    }

    /** Same rule as the custom payments: https on one of `application.revolut.checkout-origins`. */
    private fun isAllowedCheckout(checkoutUrl: String): Boolean {
        val uri = runCatching { URI(checkoutUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
        val origin = "https://" + uri.host.lowercase() + (if (uri.port == -1) "" else ":${uri.port}")
        return properties.checkoutOrigins.any { it.equals(origin, ignoreCase = true) }
    }

    /** A lock wait that ran into `lock_timeout` means another checkout / completion holds the phases -> 409. */
    private inline fun <T> lockTimeoutIsInProgress(
        reservationId: Long,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: PessimisticLockingFailureException) {
            log.warn("Revolut booking checkout for reservation {}: lock wait timed out ({}) -> 409", reservationId, e.javaClass.simpleName)
            throw CardPaymentInProgressException()
        }

    private companion object {
        /**
         * next-intl `as-needed`: English has no prefix, every other web language `/<code>`; anything else (junk, a
         * region tag, missing) -> no prefix = English.
         */
        fun localePrefix(locale: String?): String {
            val language = locale?.trim()?.let { LanguageEnum.fromLocale(it) } ?: return ""
            return if (language == LanguageEnum.EN) "" else "/${language.locale}"
        }
    }
}
