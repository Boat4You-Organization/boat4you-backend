package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MollieCreatePaymentRequest
import hr.workspace.boat4you.domains.payment.mollie.MollieMethods
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentHandler
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentHandler.HandlerResult
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentStatuses
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.service.StripeSessionExpirer
import hr.workspace.boat4you.domains.reservation.service.payment.CardAlreadyPaidException
import hr.workspace.boat4you.domains.reservation.service.payment.CardAmountAboveLimitException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentInProgressException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProvider
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import hr.workspace.boat4you.domains.reservation.service.payment.CardProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.URI
import java.util.UUID

/**
 * Mollie hosted checkout for ONLINE-BOOKING card payments (provider swap only — the phases and the amount, +5 % card
 * surcharge included, come from CardChargeCalculator exactly as for Stripe). Also the owner of the Mollie webhooks of
 * these payments ([MolliePaymentHandler]).
 *
 * No transaction here on purpose: every Mollie HTTP call happens OUTSIDE a database transaction. The database work is
 * [MollieBookingCreateTx.prepare] (one transaction that returns an outcome — M2) and the short transactions of
 * [ReservationCardOrderTx]. A checkout:
 * 1. `prepare`: reservation (404; cancelled -> 400), selection (400), card limit (422 -> bank transfer only), phase
 *    locks, then REUSE a still open PENDING payment for the same charge (no Mollie call), or STALE PENDING payments of
 *    the flow to clean up, or the plan of a new payment (CREATE);
 * 2. stale payments: read each from Mollie (one attempt): `open` -> cancel when Mollie allows it; the ledger row
 *    becomes SUPERSEDED, which is NOT final — Mollie may still take the money (cards cannot be cancelled), so the row
 *    is still read by the status endpoint, the webhook and the reconcile job until Mollie closes it, and a late
 *    payment is recorded (a second one for the same phase becomes NEEDS_REVIEW + office alert);
 *    `pending|authorized` -> 409 in progress; `paid` -> completion, then 409 already paid;
 *    `failed|expired|canceled` -> ledger closed;
 * 3. M1: open Stripe Checkout sessions of the target phases are expired (best effort, outside any transaction);
 * 4. create the payment (Idempotency-Key; error -> 502, no ledger row), check the checkout origin, insert the PENDING
 *    ledger row (a concurrent identical checkout that won the race is returned instead).
 *
 * Marking paid: ONLY from a payment read from the Mollie API (webhook, status endpoint, reconcile, stale check) —
 * the webhook body carries just the id.
 */
@Service
class MollieBookingPaymentService(
    private val properties: MollieProperties,
    private val client: MollieClient,
    private val createTx: MollieBookingCreateTx,
    private val orderTx: ReservationCardOrderTx,
    private val orders: ReservationCardOrderRepository,
    private val followUp: BookingCardCompletionFollowUp,
    private val stripeSessionExpirer: StripeSessionExpirer,
    private val alertMailer: BookingCardAlertMailer,
    @Value("\${server.host-public}") serverHostPublic: String,
) : CardPaymentProvider,
    MolliePaymentHandler {
    override val provider: CardProvider = CardProvider.MOLLIE

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
                outcome.stripeSessionIds.forEach(stripeSessionExpirer::expireQuickly)
                log.info("Mollie booking checkout for reservation {}: reusing payment {}", reservationId, outcome.orderId)
                CheckoutSessionDto(outcome.orderId, CheckoutSessionStatusEnum.PAYMENT_PENDING, outcome.checkoutUrl)
            }
            is CreateOutcome.Create -> {
                outcome.stripeSessionIds.forEach(stripeSessionExpirer::expireQuickly)
                create(outcome.plan, locale)
            }
            CreateOutcome.PaidMeanwhile -> throw CardAlreadyPaidException()
            CreateOutcome.AboveCardLimit -> throw CardAmountAboveLimitException()
            CreateOutcome.ReservationClosed -> throw ParameterValidationException(mapOf("reservationId" to "Reservation is cancelled"))
            is CreateOutcome.StalePending -> {
                // Cleaned up once already; another payment appeared meanwhile (a second tab) — do not loop.
                log.warn("Mollie booking checkout for reservation {}: pending payments again after the cleanup -> 409", reservationId)
                throw CardPaymentInProgressException()
            }
        }
    }

    /**
     * Status of a checkout this provider created, by our checkout token (`?paymentRef=` of the success page) or the
     * Mollie payment id. Unknown -> null (the router asks Stripe, then answers 404). A row still open on our side
     * (PENDING or SUPERSEDED) asks Mollie at most once per 10 s (claimCheck); a Mollie hiccup is swallowed.
     * COMPLETED -> PAYMENT_SUCCESS; Mollie closed it without money, or it is still `open` at Mollie right now ->
     * PAYMENT_NOT_COMPLETED; anything else (not read just now, in review, processing) -> PAYMENT_PENDING, which the
     * web shows as "we are confirming your payment", never as paid.
     */
    override fun checkStatus(ref: String): CheckoutSessionDto? {
        val token = runCatching { UUID.fromString(ref.trim()) }.getOrNull()
        var row =
            when {
                token != null -> orders.findByCheckoutToken(token)
                MollieClient.isValidPaymentId(ref) -> orders.findByProviderOrderId(ref)
                else -> null
            } ?: return null
        val id = row.id!!
        var liveStatus: String? = null
        if (isOpenOnOurSide(row.status) && properties.enabled && orders.claimCheck(id) == 1) {
            val payment =
                try {
                    client.getPaymentOnce(row.providerOrderId)
                } catch (e: Exception) {
                    log.warn("Mollie booking payment {}: status check could not read it ({})", row.providerOrderId, e.javaClass.simpleName)
                    null
                }
            if (payment != null) {
                liveStatus = payment.status.lowercase()
                runCatching { applyState(id, payment, event = null) }
                    .onFailure { log.error("Mollie booking payment {}: status check could not apply {}", row.providerOrderId, payment.status, it) }
                row = orders.findById(id).orElse(row)
            }
        }
        val status =
            when {
                row.status == ReservationCardOrderStatus.COMPLETED -> CheckoutSessionStatusEnum.PAYMENT_SUCCESS
                row.status == ReservationCardOrderStatus.FAILED || row.status == ReservationCardOrderStatus.CANCELLED ->
                    CheckoutSessionStatusEnum.PAYMENT_NOT_COMPLETED
                liveStatus == MolliePaymentStatuses.OPEN -> CheckoutSessionStatusEnum.PAYMENT_NOT_COMPLETED
                else -> CheckoutSessionStatusEnum.PAYMENT_PENDING
            }
        return CheckoutSessionDto(sessionIdOrOrderCode = ref, status = status)
    }

    override fun ownsPayment(paymentId: String): Boolean = orders.existsByProviderOrderId(paymentId)

    /** Webhook: re-read the payment from Mollie (retried reads), then apply. Mollie unreachable -> 503, it retries. */
    override fun onPaymentChanged(paymentId: String): HandlerResult {
        val row = orders.findByProviderOrderId(paymentId) ?: return HandlerResult.HANDLED
        val payment =
            try {
                client.getPayment(paymentId)
            } catch (e: ResourceAccessException) {
                log.warn("Mollie booking payment {}: webhook - Mollie not reachable, retry later", paymentId)
                return HandlerResult.TRANSIENT
            } catch (e: HttpServerErrorException) {
                log.warn("Mollie booking payment {}: webhook - Mollie {}, retry later", paymentId, e.statusCode.value())
                return HandlerResult.TRANSIENT
            }
        if (payment == null) {
            log.warn("Mollie booking payment {}: webhook but Mollie does not know it", paymentId)
            orderTx.stampWebhook(row.id!!, WEBHOOK_EVENT)
            return HandlerResult.HANDLED
        }
        applyState(row.id!!, payment, WEBHOOK_EVENT + ":" + payment.status)
        return HandlerResult.HANDLED
    }

    /** One run of the reconcile job: every PENDING row old enough is re-read; one failure never stops the run. */
    fun reconcileSweep() {
        if (!properties.enabled) return
        orders.findReconcileCandidateIds().forEach { id ->
            runCatching {
                val row = orders.findById(id).orElse(null) ?: return@runCatching
                val payment = client.getPayment(row.providerOrderId)
                if (payment == null) {
                    log.warn("Mollie booking payment {} (ledger {}): Mollie does not know it", row.providerOrderId, id)
                } else {
                    applyState(id, payment, event = null)
                }
            }.onFailure { log.warn("Mollie booking ledger {}: reconcile failed ({})", id, it.javaClass.simpleName) }
        }
        // Still open on our side after 48 h (Mollie expires a card checkout long before): one last read, then the office.
        orders.findStaleOpenIds().forEach { id ->
            runCatching {
                val row = orders.findById(id).orElse(null) ?: return@runCatching
                runCatching { client.getPayment(row.providerOrderId)?.let { applyState(id, it, event = null) } }
                if (orderTx.markStaleOpen(id)) {
                    orders.findById(id).orElse(null)?.let {
                        alertMailer.alert(it, "Card payment still open after 48 h", it.reviewReason ?: "still open")
                    }
                }
            }.onFailure { log.error("Mollie booking ledger {}: stale check failed", id, it) }
        }
        // Paid first payments whose booking confirmation never finished (the node died after the commit).
        orders.findStuckPromoteIds().forEach { id ->
            runCatching {
                orderTx.markPromote(id, CardOrderPromoteState.FAILED)
                orders.findById(id).orElse(null)?.let {
                    alertMailer.alert(it, "Card payment received, booking NOT confirmed", "The booking confirmation did not finish — confirm the booking by hand")
                }
            }.onFailure { log.error("Mollie booking ledger {}: stuck confirmation check failed", id, it) }
        }
    }

    private fun prepare(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
    ): CreateOutcome = lockTimeoutIsInProgress(reservationId) { createTx.prepare(reservationId, payFullAmount, paymentPhaseId, idempotencyKey) }

    /** Step 2 of the class doc. Throws the 409 / 502 the checkout must answer; returns when a new payment may be created. */
    private fun resolveStale(
        reservationId: Long,
        stale: List<StaleOrder>,
    ) {
        var completed = false
        var inProgress = false
        stale.forEach { row ->
            val payment = readForCheckout(reservationId, row)
            when (payment.status.lowercase()) {
                MolliePaymentStatuses.OPEN -> {
                    if (payment.isCancelable == true) {
                        runCatching { client.cancelPayment(row.orderId) }
                            .onFailure { log.info("Mollie booking payment {}: cancel refused ({})", row.orderId, it.javaClass.simpleName) }
                    }
                    lockTimeoutIsInProgress(reservationId) { orderTx.markSuperseded(row.ledgerId) }
                }
                MolliePaymentStatuses.PENDING, MolliePaymentStatuses.AUTHORIZED -> {
                    log.info("Mollie booking checkout for reservation {}: payment {} is {} -> 409", reservationId, row.orderId, payment.status)
                    inProgress = true
                }
                MolliePaymentStatuses.PAID -> {
                    log.info("Mollie booking checkout for reservation {}: payment {} is already paid", reservationId, row.orderId)
                    applyState(row.ledgerId, payment, event = null)
                    completed = true
                }
                MolliePaymentStatuses.CANCELED -> close(reservationId, row, ReservationCardOrderStatus.CANCELLED)
                MolliePaymentStatuses.FAILED, MolliePaymentStatuses.EXPIRED -> close(reservationId, row, ReservationCardOrderStatus.FAILED)
                else -> {
                    log.warn("Mollie booking checkout for reservation {}: payment {} in unexpected status {} -> 409", reservationId, row.orderId, payment.status)
                    inProgress = true
                }
            }
        }
        // M2: CARD_ALREADY_PAID only here, after the completion committed in its own transaction.
        if (completed) throw CardAlreadyPaidException()
        if (inProgress) throw CardPaymentInProgressException()
    }

    /** One attempt: a customer is waiting. Unreadable or unknown to Mollie -> 502, nothing changes. */
    private fun readForCheckout(
        reservationId: Long,
        row: StaleOrder,
    ): MolliePayment {
        val payment =
            try {
                client.getPaymentOnce(row.orderId)
            } catch (e: Exception) {
                log.warn("Mollie booking checkout for reservation {}: payment {} not readable ({}) -> 502", reservationId, row.orderId, e.javaClass.simpleName)
                throw CardPaymentProviderException()
            }
        if (payment == null) {
            log.warn("Mollie booking checkout for reservation {}: Mollie does not know payment {} (ledger {}) -> 502", reservationId, row.orderId, row.ledgerId)
            throw CardPaymentProviderException()
        }
        return payment
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
        val payment =
            try {
                client.createPayment(paymentRequest(plan, locale), idempotencyKey(plan))
            } catch (e: Exception) {
                log.warn("Mollie booking checkout for reservation {}: payment not created ({}: {})", plan.reservationId, e.javaClass.simpleName, e.message)
                throw CardPaymentProviderException()
            }
        if (!MollieClient.isValidPaymentId(payment.id)) {
            log.error("Mollie booking checkout for reservation {}: Mollie returned a malformed payment id", plan.reservationId)
            throw CardPaymentProviderException()
        }
        val checkoutUrl = payment.checkoutUrl
        if (checkoutUrl == null || !isAllowedCheckout(checkoutUrl)) {
            log.error("Mollie booking checkout for reservation {}: payment {} has no checkout URL on an allowed origin", plan.reservationId, payment.id)
            cancelQuietly(payment)
            throw CardPaymentProviderException()
        }
        val inserted =
            try {
                lockTimeoutIsInProgress(plan.reservationId) { orderTx.insertPending(plan, payment.id, checkoutUrl) }
            } catch (e: Exception) {
                // Nobody has the checkout URL yet: try to make the payment unpayable (cards usually expire on their own).
                cancelQuietly(payment)
                throw e
            }
        if (inserted.existing && inserted.order.status == ReservationCardOrderStatus.COMPLETED) throw CardAlreadyPaidException()
        if (inserted.existing && inserted.order.status != ReservationCardOrderStatus.PENDING) {
            // Mollie replayed a payment that is no longer open on our side: the next click is a new attempt (new key).
            log.warn(
                "Mollie booking checkout for reservation {}: replayed payment {} is {} -> 502, try again",
                plan.reservationId,
                payment.id,
                inserted.order.status,
            )
            throw CardPaymentProviderException()
        }
        if (inserted.existing) {
            if (inserted.order.providerOrderId != payment.id) {
                log.info(
                    "Mollie booking checkout for reservation {}: a concurrent checkout created payment {} first; dropping {}",
                    plan.reservationId,
                    inserted.order.providerOrderId,
                    payment.id,
                )
                cancelQuietly(payment)
            }
            return CheckoutSessionDto(inserted.order.providerOrderId, CheckoutSessionStatusEnum.PAYMENT_PENDING, inserted.order.checkoutUrl)
        }
        log.info(
            "Mollie booking payment {} created for reservation {} (phases [{}], {} cents)",
            payment.id,
            plan.reservationId,
            plan.phaseIds,
            plan.amountMinor,
        )
        return CheckoutSessionDto(payment.id, CheckoutSessionStatusEnum.PAYMENT_PENDING, checkoutUrl)
    }

    private fun paymentRequest(
        plan: BookingOrderPlan,
        locale: String?,
    ): MollieCreatePaymentRequest {
        val prefix = localePrefix(locale)
        return MollieCreatePaymentRequest(
            amount = MollieAmount.eur(plan.amountMinor),
            description = "Boat4you reservation #${plan.reference}",
            redirectUrl = "$hostPublic$prefix/payment-success?reservationId=${plan.reservationId}&paymentRef=${plan.checkoutToken}",
            cancelUrl = "$hostPublic$prefix/payment-cancelled?reservationId=${plan.reservationId}",
            webhookUrl = properties.webhookUrl,
            locale = mollieLocale(locale),
            // Card surcharge applies: cards and Apple Pay only (no Pay by Bank / Klarna on the +5 % path).
            method = listOf(MollieMethods.CREDIT_CARD, MollieMethods.APPLE_PAY),
            metadata =
                buildMap {
                    put("reservationId", plan.reservationId.toString())
                    put(ReservationCardOrderTx.FLOW_METADATA_KEY, plan.reservationFlowId.toString())
                    put(ReservationCardOrderTx.PHASES_METADATA_KEY, plan.phaseIds)
                    put("checkoutToken", plan.checkoutToken.toString())
                    plan.payFullAmount?.let { put("payFullAmount", it.toString()) }
                    plan.paymentPhaseId?.let { put("paymentPhaseId", it.toString()) }
                },
        )
    }

    /**
     * Mollie Idempotency-Key = our checkout token: derived from (flow, phases, cents, web key, attempt) when the web
     * sent its key — a double click repeats the same request and Mollie returns the same payment — else random.
     */
    private fun idempotencyKey(plan: BookingOrderPlan): String = "booking-${plan.checkoutToken}"

    /**
     * Applies what Mollie says about a payment (webhook, status endpoint, reconcile, stale check). `paid` -> completion
     * (idempotent) and then the refund / chargeback totals, each alerting the office once when it grows.
     */
    private fun applyState(
        ledgerId: Long,
        payment: MolliePayment,
        event: String?,
    ) {
        when (payment.status.lowercase()) {
            MolliePaymentStatuses.PAID -> {
                val result = orderTx.applyCompleted(ledgerId, payment, event)
                followUp.afterCompletion(ledgerId, result, cardLabel(payment))
                orderTx.applyMoneyBack(ledgerId, payment)?.let { reason ->
                    orders.findById(ledgerId).orElse(null)?.let { alertMailer.alert(it, "Card payment refunded or charged back", reason) }
                }
            }
            MolliePaymentStatuses.CANCELED -> orderTx.closeIfPending(ledgerId, ReservationCardOrderStatus.CANCELLED)
            MolliePaymentStatuses.FAILED, MolliePaymentStatuses.EXPIRED -> orderTx.closeIfPending(ledgerId, ReservationCardOrderStatus.FAILED)
            else -> if (event != null) orderTx.stampWebhook(ledgerId, event)
        }
    }

    /** "Visa •••• 9996" from Mollie's card details (label + last four digits); null when Mollie sent neither. */
    private fun cardLabel(payment: MolliePayment): String? {
        val brand = payment.details?.cardLabel?.takeIf { CARD_BRAND.matches(it) }
        val lastFour = payment.details?.cardNumber?.takeIf { LAST_FOUR.matches(it) }?.let { "•••• $it" }
        return listOfNotNull(brand, lastFour).joinToString(" ").ifBlank { null }
    }

    private fun isOpenOnOurSide(status: ReservationCardOrderStatus): Boolean =
        status == ReservationCardOrderStatus.PENDING || status == ReservationCardOrderStatus.SUPERSEDED

    private fun cancelQuietly(payment: MolliePayment) {
        if (payment.isCancelable != true) return
        runCatching { client.cancelPayment(payment.id) }
            .onFailure { log.warn("Mollie booking payment {}: cancel failed ({}); it expires on its own", payment.id, it.javaClass.simpleName) }
    }

    /** Same rule as the custom payments: https on one of `application.mollie.checkout-origins`. */
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
            log.warn("Mollie booking checkout for reservation {}: lock wait timed out ({}) -> 409", reservationId, e.javaClass.simpleName)
            throw CardPaymentInProgressException()
        }

    private companion object {
        const val WEBHOOK_EVENT = "mollie-webhook"
        val CARD_BRAND = Regex("^[A-Za-z_ ]{1,32}$")
        val LAST_FOUR = Regex("^[0-9]{4}$")

        /**
         * next-intl `as-needed`: English has no prefix, every other web language `/<code>`; anything else (junk, a
         * region tag, missing) -> no prefix = English.
         */
        fun localePrefix(locale: String?): String {
            val language = locale?.trim()?.let { LanguageEnum.fromLocale(it) } ?: return ""
            return if (language == LanguageEnum.EN) "" else "/${language.locale}"
        }

        /** Mollie checkout language; Mollie has no Croatian -> English. */
        fun mollieLocale(locale: String?): String =
            when (locale?.trim()?.lowercase()?.take(2)) {
                "de" -> "de_DE"
                "fr" -> "fr_FR"
                "it" -> "it_IT"
                "es" -> "es_ES"
                "pt" -> "pt_PT"
                "nl" -> "nl_NL"
                "pl" -> "pl_PL"
                else -> "en_US"
            }
    }
}
