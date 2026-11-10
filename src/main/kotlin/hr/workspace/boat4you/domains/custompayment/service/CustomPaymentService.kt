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
import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.custompayment.dto.KlarnaOfferDto
import hr.workspace.boat4you.domains.payment.mollie.KlarnaBilling
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MollieKlarnaProperties
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentLine
import hr.workspace.boat4you.domains.payment.mollie.MollieCreatePaymentRequest
import hr.workspace.boat4you.domains.payment.mollie.MollieMethods
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentHandler
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentHandler.HandlerResult
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentStatuses
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
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
 * The secret `/pay/<linkKey>` card payments: config, create (-> Mollie hosted checkout), status, webhook and
 * reconcile. No class-level transaction on purpose: every Mollie HTTP call happens OUTSIDE a database transaction;
 * the database writes are the short transactions of [CustomPaymentTx].
 */
@Service
class CustomPaymentService(
    private val properties: MollieProperties,
    private val tx: CustomPaymentTx,
    private val repository: CustomPaymentRepository,
    private val reservationRepository: ReservationRepository,
    private val client: MollieClient,
    private val klarnaProperties: MollieKlarnaProperties,
) : MolliePaymentHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val linkKeys = CustomPaymentLinkKeys(properties.customPayment.linkKeys)

    init {
        log.info("Custom payments: link keys per site {}", linkKeys.summary().ifEmpty { "none" })
    }

    /** 404 for an unknown (site, linkKey) pair; `enabled=false` while Mollie is not configured. */
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
            klarna = if (klarnaProperties.enabled) KlarnaOfferDto(klarnaProperties.countries, klarnaProperties.maxAmountMinor) else null,
        )
    }

    /**
     * Creates the attempt and its Mollie payment and returns the hosted checkout URL. 503 disabled, 404 wrong
     * (site, linkKey), 400 invalid fields (amount above the Mollie card limit included), 429 more than
     * [MAX_ATTEMPTS_PER_EMAIL] attempts per e-mail per hour, 502 Mollie error. The same data again while the first
     * checkout is still usable returns that checkout.
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
                method = if (input.klarna != null) METHOD_KLARNA else METHOD_CARD,
            )
        // A Klarna checkout carries the billing address (not stored here), so it is never handed out again: every
        // Klarna submit is its own payment. Card attempts dedupe as before, among card attempts only.
        val inserted = tx.insertPending(draft, if (input.klarna != null) now else now.minus(dedupeWindow()))
        val payment = inserted.payment
        val id = payment.id!!
        val token = payment.publicToken.toString()
        if (inserted.existing) {
            log.info("Custom payment {}: same attempt again, returning its checkout", id)
            return CreateCustomPaymentResponse(payment.checkoutUrl!!, token)
        }

        val molliePayment =
            try {
                client.createPayment(paymentRequest(site, linkKey, input, id, token), idempotencyKey = "custom-payment-$token")
            } catch (e: Exception) {
                log.warn("Custom payment {}: Mollie payment not created ({}: {})", id, e.javaClass.simpleName, e.message)
                tx.markCreateFailed(id)
                throw CustomPaymentProviderException()
            }
        val checkoutUrl = molliePayment.checkoutUrl
        if (checkoutUrl == null || !isAllowedCheckout(checkoutUrl)) {
            log.error("Custom payment {}: Mollie payment {} has no checkout URL on an allowed origin", id, molliePayment.id)
            tx.markCreateFailed(id, molliePayment.id.takeIf { MollieClient.isValidPaymentId(it) })
            throw CustomPaymentProviderException()
        }
        tx.attachOrder(id, molliePayment.id, checkoutUrl)
        log.info("Custom payment {} created for site {} (Mollie payment {})", id, site.key, molliePayment.id)
        return CreateCustomPaymentResponse(checkoutUrl, token)
    }

    /**
     * Status for the success page. Unknown, malformed or older than 30 days -> 404. A PENDING row asks Mollie at most
     * once per 10 s (claimCheck); a Mollie hiccup is swallowed and the stored status is returned.
     *
     * A row that is still PENDING while Mollie's payment is `open` means the payer came back WITHOUT finishing (Mollie
     * keeps a payment open after a declined card so the payer can try again, and its "back" link returns here). The
     * page then gets CANCELLED (-> "not completed, try again") although the row itself stays PENDING: a late payment
     * on the old checkout is still picked up by the webhook.
     */
    fun status(token: String): CustomPaymentStatusDto {
        val uuid = runCatching { UUID.fromString(token.trim()) }.getOrNull() ?: throw CustomPaymentNotFoundException()
        var payment = repository.findByPublicToken(uuid) ?: throw CustomPaymentNotFoundException()
        if (payment.createdAt.isBefore(Instant.now().minus(STATUS_LIFETIME))) throw CustomPaymentNotFoundException()

        val orderId = payment.providerOrderId
        val id = payment.id!!
        if (orderId != null && mayAskMollie(payment) && repository.claimCheck(id) == 1) {
            val molliePayment =
                try {
                    client.getPaymentOnce(orderId)
                } catch (e: Exception) {
                    log.warn("Custom payment {}: status check could not read the Mollie payment ({})", id, e.javaClass.simpleName)
                    null
                }
            if (molliePayment != null) {
                apply(payment, molliePayment, event = null)
                payment = repository.findById(id).orElse(payment)
            }
        }
        val shown =
            if (payment.status == CustomPaymentStatus.PENDING && lastKnownMollieStatus(payment) == MolliePaymentStatuses.OPEN) {
                CustomPaymentStatus.CANCELLED
            } else {
                payment.status
            }
        return CustomPaymentStatusDto(
            status = shown,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            reference = payment.reference,
            site = payment.site,
        )
    }

    private fun mayAskMollie(payment: CustomPayment): Boolean = properties.enabled && payment.status == CustomPaymentStatus.PENDING

    /** The Mollie status stored with the last check (snapshot), null when never read. */
    private fun lastKnownMollieStatus(payment: CustomPayment): String? = OrderSnapshot.parseOrNull(payment.orderSnapshot)?.state

    override fun ownsPayment(paymentId: String): Boolean = repository.existsByProviderOrderId(paymentId)

    /**
     * Webhook: Mollie sends only the id, so the payment is ALWAYS re-read from the Mollie API before anything is
     * marked; the verdict and the refunded total (a refund also triggers this call) are applied from that read.
     */
    override fun onPaymentChanged(paymentId: String): HandlerResult {
        val payment = repository.findByProviderOrderId(paymentId) ?: return HandlerResult.HANDLED
        val id = payment.id!!
        val molliePayment =
            try {
                client.getPayment(paymentId)
            } catch (e: ResourceAccessException) {
                log.warn("Custom payment {}: webhook - Mollie not reachable, retry later", id)
                return HandlerResult.TRANSIENT
            } catch (e: HttpServerErrorException) {
                log.warn("Custom payment {}: webhook - Mollie {}, retry later", id, e.statusCode.value())
                return HandlerResult.TRANSIENT
            }
        if (molliePayment == null) {
            log.warn("Custom payment {}: webhook but Mollie does not know payment {}", id, paymentId)
            tx.stampWebhook(id, WEBHOOK_EVENT)
            return HandlerResult.HANDLED
        }
        apply(payment, molliePayment, WEBHOOK_EVENT + ":" + molliePayment.status)
        return HandlerResult.HANDLED
    }

    /**
     * Re-reads the payment from Mollie and applies the verdict (+ the refunded total) — reconcile job and admin
     * Refresh. 404 unknown id, 503 disabled, 502 Mollie error. Returns the row as stored afterwards.
     */
    fun reconcile(id: Long): CustomPayment {
        val payment = repository.findById(id).orElseThrow { CustomPaymentNotFoundException() }
        val orderId = payment.providerOrderId ?: return payment
        if (!properties.enabled) throw CustomPaymentsUnavailableException()
        val molliePayment =
            try {
                client.getPayment(orderId)
            } catch (e: Exception) {
                log.warn("Custom payment {}: reconcile could not read the Mollie payment ({})", id, e.javaClass.simpleName)
                throw CustomPaymentProviderException()
            }
        if (molliePayment == null) {
            log.warn("Custom payment {}: Mollie does not know payment {}", id, orderId)
        } else {
            apply(payment, molliePayment, event = null)
        }
        return repository.findById(id).orElse(payment)
    }

    /** One run of the reconcile job (CustomPaymentReconcileJob). Each row on its own; one failure never stops the run. */
    fun reconcileSweep() {
        if (!properties.enabled) return
        val failed = tx.failUnattached()
        if (failed > 0) log.warn("Custom payments: {} attempt(s) without a Mollie payment after 15 min -> FAILED", failed)

        repository.findReconcileCandidateIds().forEach { id ->
            runCatching { reconcile(id) }.onFailure { log.warn("Custom payment {}: reconcile failed ({})", id, it.javaClass.simpleName) }
        }
        repository.findStalePendingIds().forEach { id ->
            // One last look first: an expired payment turns into FAILED instead of a needless review.
            runCatching { reconcile(id) }
            runCatching { tx.markStale(id) }.onFailure { log.error("Custom payment {}: stale check failed", id, it) }
        }
    }

    private fun apply(
        payment: CustomPayment,
        molliePayment: MolliePayment,
        event: String?,
    ) {
        val verdict = CustomPaymentVerdict.of(molliePayment, payment)
        tx.applyVerdict(payment.id!!, verdict, OrderSnapshot.of(molliePayment).toJson(), event)
        val refunded = molliePayment.amountRefunded?.toMinorOrNull() ?: 0L
        if (refunded > 0L) tx.applyRefund(payment.id!!, refunded)
        val chargedBack = molliePayment.amountChargedBack?.toMinorOrNull() ?: 0L
        if (chargedBack > 0L) tx.applyChargeback(payment.id!!, chargedBack)
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
        val klarna: KlarnaBilling? = null,
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
        // Klarna: the e-mail of the form is the billing e-mail; 422 (8107) when the country or amount does not qualify.
        val klarna = request.klarna?.copy(email = email)?.toBilling(email)?.also { klarnaProperties.requireAvailable(it, amountMinor!!) }
        return ValidInput(reference, amountMinor!!, email, locale, klarna)
    }

    private fun toMinor(amount: BigDecimal?): Long? = amount?.let { runCatching { it.stripTrailingZeros().longValueExact() }.getOrNull() }

    private fun reservationIdFor(reference: String): Long? =
        runCatching { reservationRepository.findByReservationNumber(reference)?.id }
            .onFailure { log.warn("Custom payment: reservation lookup failed ({})", it.javaClass.simpleName) }
            .getOrNull()

    private fun paymentRequest(
        site: CustomPaymentSite,
        linkKey: String,
        input: ValidInput,
        id: Long,
        token: String,
    ): MollieCreatePaymentRequest {
        val origin = properties.customPayment.returnBaseUrl ?: site.baseUrl
        val payPage = "$origin${site.localePrefix(input.locale)}/pay/$linkKey"
        return MollieCreatePaymentRequest(
            amount = MollieAmount.eur(input.amountMinor),
            description = "${site.brandName} - contract ${input.reference}",
            redirectUrl = "$payPage/success?ref=$token",
            cancelUrl = "$payPage/failed?ref=$token",
            webhookUrl = properties.webhookUrl,
            locale = MOLLIE_LOCALES[input.locale] ?: DEFAULT_MOLLIE_LOCALE,
            method = if (input.klarna != null) listOf(MollieMethods.KLARNA) else CUSTOM_PAYMENT_METHODS,
            billingAddress = input.klarna?.toMollie(),
            lines = input.klarna?.let { MolliePaymentLine.of("${site.brandName} - contract ${input.reference}", input.amountMinor, 0, "Payment fee") },
            metadata =
                mapOf(
                    CustomPaymentVerdict.TOKEN_METADATA_KEY to token,
                    "customPaymentId" to id.toString(),
                    "site" to site.key,
                ),
        )
    }

    private fun isAllowedCheckout(checkoutUrl: String): Boolean {
        val uri = runCatching { URI(checkoutUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
        val origin = "https://" + uri.host.lowercase() + (if (uri.port == -1) "" else ":${uri.port}")
        return properties.checkoutOrigins.any { it.equals(origin, ignoreCase = true) }
    }

    /**
     * The dedupe window: 5/6 of `order-ttl` (default 15 min -> 12.5 min), at most 50 min, so a returned checkout is
     * never one Mollie has already expired.
     */
    private fun dedupeWindow(): Duration = minOf(DEDUPE_WINDOW, properties.customPayment.orderTtl.multipliedBy(5).dividedBy(6))

    private companion object {
        const val MAX_ATTEMPTS_PER_EMAIL = 5L
        const val MAX_EMAIL_LENGTH = 254
        const val MAX_CLIENT_IP_LENGTH = 64
        const val WEBHOOK_EVENT = "mollie-webhook"
        const val DEFAULT_MOLLIE_LOCALE = "en_US"

        /** Cards (incl. Google Pay inside the card page), Apple Pay and Pay by Bank — no Klarna, no slow bank transfer. */
        val CUSTOM_PAYMENT_METHODS = listOf(MollieMethods.CREDIT_CARD, MollieMethods.APPLE_PAY, MollieMethods.PAY_BY_BANK)
        const val METHOD_CARD = "CARD"
        const val METHOD_KLARNA = "KLARNA"

        /** Our site locales -> Mollie checkout locales (Mollie has no Croatian: hr -> English). */
        val MOLLIE_LOCALES =
            mapOf(
                "en" to "en_US",
                "de" to "de_DE",
                "fr" to "fr_FR",
                "it" to "it_IT",
                "es" to "es_ES",
                "pt" to "pt_PT",
                "nl" to "nl_NL",
                "pl" to "pl_PL",
            )
        val EMAIL_WINDOW: Duration = Duration.ofHours(1)
        val DEDUPE_WINDOW: Duration = Duration.ofMinutes(50)
        val STATUS_LIFETIME: Duration = Duration.ofDays(30)
        val REFERENCE = Regex("^[A-Za-z0-9/_. -]{3,64}$")
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
