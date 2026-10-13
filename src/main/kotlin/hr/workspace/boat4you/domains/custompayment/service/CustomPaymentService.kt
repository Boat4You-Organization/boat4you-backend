package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.custompayment.CustomPaymentSite
import hr.workspace.boat4you.domains.custompayment.CustomPaymentSites
import hr.workspace.boat4you.domains.custompayment.dto.CreateCustomPaymentRequest
import hr.workspace.boat4you.domains.custompayment.dto.CreateCustomPaymentResponse
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentConfigDto
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentStatusDto
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentNotFoundException
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentProviderException
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentTooManyException
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentsUnavailableException
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.payment.revolut.RevolutCaptureMode
import hr.workspace.boat4you.domains.payment.revolut.RevolutCreateOrderRequest
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantClient
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderEventHandler
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderEventHandler.HandlerResult
import hr.workspace.boat4you.domains.payment.revolut.RevolutProperties
import hr.workspace.boat4you.domains.payment.revolut.RevolutWebhookEvents
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The secret `/pay/<linkKey>` card payments: config, create (-> Revolut hosted checkout), status, webhook events and
 * reconcile. No class-level transaction on purpose: every Revolut HTTP call happens OUTSIDE a database transaction;
 * the database writes are the short transactions of [CustomPaymentTx].
 */
@Service
class CustomPaymentService(
    private val properties: RevolutProperties,
    private val tx: CustomPaymentTx,
    private val repository: CustomPaymentRepository,
    private val reservationRepository: ReservationRepository,
    private val client: RevolutMerchantClient,
) : RevolutOrderEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val linkKeys = CustomPaymentLinkKeys(properties.customPayment.linkKeys)

    init {
        log.info("Custom payments: link keys per site {}", linkKeys.summary().ifEmpty { "none" })
    }

    /** 404 for an unknown (site, linkKey) pair; `enabled=false` while Revolut is not configured. */
    fun config(
        site: String?,
        linkKey: String?,
    ): CustomPaymentConfigDto {
        requireSite(site, linkKey)
        val settings = properties.customPayment
        return CustomPaymentConfigDto(
            enabled = properties.enabled,
            minAmountMinor = settings.minAmountMinor,
            maxAmountMinor = settings.maxAmountMinor,
            currency = CustomPayment.CURRENCY_EUR,
            termsVersion = settings.termsVersion,
        )
    }

    /**
     * Creates the attempt and its Revolut order and returns the hosted checkout URL. 503 disabled, 404 wrong
     * (site, linkKey), 400 invalid fields, 429 more than [MAX_ATTEMPTS_PER_EMAIL] attempts per e-mail per hour,
     * 502 Revolut error. The same data again while the first checkout is still usable returns that checkout.
     */
    fun create(
        request: CreateCustomPaymentRequest,
        clientIp: String?,
    ): CreateCustomPaymentResponse {
        if (!properties.enabled) throw CustomPaymentsUnavailableException()
        val site = requireSite(request.site, request.linkKey)
        val linkKey = request.linkKey!!.trim()
        val input = validate(request, site)

        val now = Instant.now()
        if (repository.countByEmailSince(input.email, now.minus(EMAIL_WINDOW)) >= MAX_ATTEMPTS_PER_EMAIL) {
            log.warn("Custom payment rejected: more than {} attempts from one e-mail within an hour ({})", MAX_ATTEMPTS_PER_EMAIL, site.key)
            throw CustomPaymentTooManyException()
        }

        val draft =
            CustomPaymentDraft(
                site = site.key,
                reference = input.reference,
                amountMinor = input.amountMinor,
                payerEmail = input.email,
                clientIp = clientIp?.trim()?.take(MAX_CLIENT_IP_LENGTH)?.ifEmpty { null },
                locale = input.locale,
                termsVersion = properties.customPayment.termsVersion,
                acceptedAt = now,
                reservationId = reservationIdFor(input.reference),
            )
        val inserted = tx.insertPending(draft, now.minus(dedupeWindow()))
        val payment = inserted.payment
        val id = payment.id!!
        val token = payment.publicToken.toString()
        if (inserted.existing) {
            log.info("Custom payment {}: same attempt again, returning its checkout", id)
            return CreateCustomPaymentResponse(payment.checkoutUrl!!, token)
        }

        val order =
            try {
                client.createOrder(orderRequest(site, linkKey, input, id, token))
            } catch (e: Exception) {
                log.warn("Custom payment {}: Revolut order not created ({}: {})", id, e.javaClass.simpleName, e.message)
                tx.markCreateFailed(id)
                throw CustomPaymentProviderException()
            }
        val checkoutUrl = order.checkoutUrl
        if (checkoutUrl == null || !isAllowedCheckout(checkoutUrl)) {
            log.error("Custom payment {}: Revolut order {} has no checkout URL on an allowed origin", id, order.id)
            tx.markCreateFailed(id, order.id.takeIf { RevolutMerchantClient.isValidOrderId(it) })
            throw CustomPaymentProviderException()
        }
        tx.attachOrder(id, order.id, checkoutUrl)
        log.info("Custom payment {} created for site {} (Revolut order {})", id, site.key, order.id)
        return CreateCustomPaymentResponse(checkoutUrl, token)
    }

    /**
     * Status for the success page. Unknown, malformed or older than 30 days -> 404. A PENDING row asks Revolut at most
     * once per 10 s (claimCheck); a Revolut hiccup is swallowed and the stored status is returned.
     */
    fun status(token: String): CustomPaymentStatusDto {
        val uuid = runCatching { UUID.fromString(token.trim()) }.getOrNull() ?: throw CustomPaymentNotFoundException()
        var payment = repository.findByPublicToken(uuid) ?: throw CustomPaymentNotFoundException()
        if (payment.createdAt.isBefore(Instant.now().minus(STATUS_LIFETIME))) throw CustomPaymentNotFoundException()

        val orderId = payment.providerOrderId
        val id = payment.id!!
        if (orderId != null && mayAskRevolut(payment) && repository.claimCheck(id) == 1) {
            val order =
                try {
                    client.getOrder(orderId)
                } catch (e: Exception) {
                    log.warn("Custom payment {}: status check could not read the Revolut order ({})", id, e.javaClass.simpleName)
                    null
                }
            if (order != null) {
                apply(payment, order, event = null)
                payment = repository.findById(id).orElse(payment)
            }
        }
        return CustomPaymentStatusDto(
            status = payment.status,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            reference = payment.reference,
            site = payment.site,
        )
    }

    private fun mayAskRevolut(payment: CustomPayment): Boolean = properties.enabled && payment.status == CustomPaymentStatus.PENDING

    override fun ownsOrder(orderId: String): Boolean = repository.existsByProviderOrderId(orderId)

    /**
     * Webhook. A declined / failed card attempt only stamps the row (the payer retries on the same hosted page);
     * anything else re-reads the order from Revolut (the payload carries no state) and applies the verdict.
     */
    override fun onEvent(
        event: String,
        orderId: String,
    ): HandlerResult {
        val payment = repository.findByProviderOrderId(orderId) ?: return HandlerResult.HANDLED
        val id = payment.id!!
        if (event == RevolutWebhookEvents.ORDER_PAYMENT_DECLINED || event == RevolutWebhookEvents.ORDER_PAYMENT_FAILED) {
            tx.stampWebhook(id, event)
            log.info("Custom payment {}: {} (the payer can retry on the same checkout)", id, event)
            return HandlerResult.HANDLED
        }
        val order =
            try {
                client.getOrder(orderId)
            } catch (e: ResourceAccessException) {
                log.warn("Custom payment {}: webhook {} - Revolut not reachable, retry later", id, event)
                return HandlerResult.TRANSIENT
            } catch (e: HttpServerErrorException) {
                log.warn("Custom payment {}: webhook {} - Revolut {}, retry later", id, event, e.statusCode.value())
                return HandlerResult.TRANSIENT
            }
        if (order == null) {
            log.warn("Custom payment {}: webhook {} but Revolut does not know order {}", id, event, orderId)
            tx.stampWebhook(id, event)
            return HandlerResult.HANDLED
        }
        apply(payment, order, event)
        return HandlerResult.HANDLED
    }

    /** A refund order completed for one of our orders: take Revolut's running total from the original order. */
    override fun onRefund(
        originalOrderId: String,
        refundOrder: RevolutOrder,
    ) {
        val payment = repository.findByProviderOrderId(originalOrderId) ?: return
        val total =
            runCatching { client.getOrder(originalOrderId)?.refundedAmount }
                .onFailure { log.warn("Custom payment {}: refund total not readable ({})", payment.id, it.javaClass.simpleName) }
                .getOrNull()
                ?: refundOrder.amount
        tx.applyRefund(payment.id!!, total)
    }

    /**
     * Re-reads the order from Revolut and applies the verdict (+ the refunded total) — reconcile job and admin
     * Refresh. 404 unknown id, 503 disabled, 502 Revolut error. Returns the row as stored afterwards.
     */
    fun reconcile(id: Long): CustomPayment {
        val payment = repository.findById(id).orElseThrow { CustomPaymentNotFoundException() }
        val orderId = payment.providerOrderId ?: return payment
        if (!properties.enabled) throw CustomPaymentsUnavailableException()
        val order =
            try {
                client.getOrder(orderId)
            } catch (e: Exception) {
                log.warn("Custom payment {}: reconcile could not read the Revolut order ({})", id, e.javaClass.simpleName)
                throw CustomPaymentProviderException()
            }
        if (order == null) {
            log.warn("Custom payment {}: Revolut does not know order {}", id, orderId)
        } else {
            apply(payment, order, event = null)
        }
        return repository.findById(id).orElse(payment)
    }

    /** One run of the reconcile job (CustomPaymentReconcileJob). Each row on its own; one failure never stops the run. */
    fun reconcileSweep() {
        if (!properties.enabled) return
        val failed = tx.failUnattached()
        if (failed > 0) log.warn("Custom payments: {} attempt(s) without a Revolut order after 15 min -> FAILED", failed)

        repository.findReconcileCandidateIds().forEach { id ->
            runCatching { reconcile(id) }.onFailure { log.warn("Custom payment {}: reconcile failed ({})", id, it.javaClass.simpleName) }
        }
        repository.findStalePendingIds().forEach { id ->
            // One last look first: an expired order turns into FAILED instead of a needless review.
            runCatching { reconcile(id) }
            runCatching { tx.markStale(id) }.onFailure { log.error("Custom payment {}: stale check failed", id, it) }
        }
    }

    private fun apply(
        payment: CustomPayment,
        order: RevolutOrder,
        event: String?,
    ) {
        val verdict = CustomPaymentVerdict.of(order, payment)
        tx.applyVerdict(payment.id!!, verdict, OrderSnapshot.of(order).toJson(), event)
        val refunded = order.refundedAmount ?: 0L
        if (refunded > 0L) tx.applyRefund(payment.id!!, refunded)
    }

    private fun requireSite(
        site: String?,
        linkKey: String?,
    ): CustomPaymentSite = linkKeys.resolve(site, linkKey) ?: throw CustomPaymentNotFoundException()

    private data class ValidInput(
        val reference: String,
        val amountMinor: Long,
        val email: String,
        val locale: String,
    )

    private fun validate(
        request: CreateCustomPaymentRequest,
        site: CustomPaymentSite,
    ): ValidInput {
        val errors = linkedMapOf<String, String>()
        val reference = request.reference?.trim().orEmpty()
        if (!REFERENCE.matches(reference)) errors["reference"] = "3-64 characters: letters, digits, space and / _ . -"

        val settings = properties.customPayment
        val amountMinor = toMinor(request.amountMinor)
        if (amountMinor == null || amountMinor < settings.minAmountMinor || amountMinor > settings.maxAmountMinor) {
            errors["amountMinor"] = "must be whole cents between ${settings.minAmountMinor} and ${settings.maxAmountMinor}"
        }

        val email = request.email?.trim().orEmpty()
        if (email.length > MAX_EMAIL_LENGTH || !EMAIL.matches(email)) errors["email"] = "must be a valid e-mail address"

        if (request.acceptTerms != true) errors["acceptTerms"] = "must be true"

        val locale = request.locale?.trim()?.lowercase()?.ifEmpty { null } ?: CustomPaymentSites.DEFAULT_LOCALE
        if (!site.supportsLocale(locale)) errors["locale"] = "must be one of ${site.locales}"

        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        return ValidInput(reference, amountMinor!!, email, locale)
    }

    private fun toMinor(amount: BigDecimal?): Long? = amount?.let { runCatching { it.stripTrailingZeros().longValueExact() }.getOrNull() }

    private fun reservationIdFor(reference: String): Long? =
        runCatching { reservationRepository.findByReservationNumber(reference)?.id }
            .onFailure { log.warn("Custom payment: reservation lookup failed ({})", it.javaClass.simpleName) }
            .getOrNull()

    private fun orderRequest(
        site: CustomPaymentSite,
        linkKey: String,
        input: ValidInput,
        id: Long,
        token: String,
    ) = RevolutCreateOrderRequest(
        amount = input.amountMinor,
        currency = CustomPayment.CURRENCY_EUR,
        captureMode = RevolutCaptureMode.AUTOMATIC,
        description = "${site.brandName} payment — ${input.reference}",
        redirectUrl = "${site.baseUrl}${site.localePrefix(input.locale)}/pay/$linkKey/success?ref=$token",
        merchantOrderData = RevolutMerchantOrderData(reference = token),
        metadata = mapOf("customPaymentId" to id.toString()),
        expirePendingAfter = properties.customPayment.orderTtl.toString(),
        enforceChallenge = ENFORCE_CHALLENGE_FORCED,
    )

    private fun isAllowedCheckout(checkoutUrl: String): Boolean {
        val uri = runCatching { URI(checkoutUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
        val origin = "https://" + uri.host.lowercase() + (if (uri.port == -1) "" else ":${uri.port}")
        return properties.checkoutOrigins.any { it.equals(origin, ignoreCase = true) }
    }

    /**
     * The dedupe window: 50 min with the default 1 h order lifetime, shorter with a shorter `order-ttl` (sandbox
     * PT3M), so a returned checkout is never one Revolut has already expired.
     */
    private fun dedupeWindow(): Duration = minOf(DEDUPE_WINDOW, properties.customPayment.orderTtl.multipliedBy(5).dividedBy(6))

    private companion object {
        const val MAX_ATTEMPTS_PER_EMAIL = 5L
        const val MAX_EMAIL_LENGTH = 254
        const val MAX_CLIENT_IP_LENGTH = 64
        const val ENFORCE_CHALLENGE_FORCED = "forced"
        val EMAIL_WINDOW: Duration = Duration.ofHours(1)
        val DEDUPE_WINDOW: Duration = Duration.ofMinutes(50)
        val STATUS_LIFETIME: Duration = Duration.ofDays(30)
        val REFERENCE = Regex("^[A-Za-z0-9/_. -]{3,64}$")
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
