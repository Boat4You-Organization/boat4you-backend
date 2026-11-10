package hr.workspace.boat4you.domains.payment.mollie

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * `application.mollie.*` — Mollie Payments API settings shared by the secret `/pay/<key>` custom payments and the
 * online-booking card payments. Every key has a blank / safe default so a node without the env vars (cusma3, local dev)
 * keeps starting: the feature then reports itself disabled instead of failing the boot.
 *
 * [enabled] only when the API key is set, and never when the `prod` profile holds a TEST key (ERROR at startup — real
 * customers must not be sent to a test checkout). Not a data class on purpose: a generated toString() would print the key.
 */
@Component
class MollieProperties(
    @Value("\${application.mollie.base-url:https://api.mollie.com}") baseUrl: String,
    @Value("\${application.mollie.api-key:}") apiKey: String,
    @Value("\${application.mollie.webhook-url:}") webhookUrl: String,
    @Value("\${application.mollie.reconcile-enabled:false}") val reconcileEnabled: Boolean,
    @Value("\${application.mollie.checkout-origins:https://www.mollie.com}") checkoutOrigins: String,
    @Value("\${application.mollie.card-max-amount:10000}") cardMaxAmount: Long,
    @Value("\${application.mollie.custom-payment.min-amount-minor:5000}") customPaymentMinAmountMinor: Long,
    @Value("\${application.mollie.custom-payment.order-ttl:PT15M}") customPaymentOrderTtl: Duration,
    @Value("\${application.mollie.custom-payment.terms-version:2026-11-10}") customPaymentTermsVersion: String,
    @Value("\${application.mollie.custom-payment.link-keys:}") customPaymentLinkKeys: String,
    environment: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** API root without a trailing slash (`https://api.mollie.com`; a stub in tests). */
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    /** `live_…` / `test_…` API key of the Boat4You website profile. Never logged. */
    val apiKey: String = apiKey.trim()

    /** Public webhook URL (`https://api.boat4you.com/webhooks/mollie`); blank = no webhook (local). */
    val webhookUrl: String? = webhookUrl.trim().ifEmpty { null }

    /** Origins a checkout URL returned by Mollie may have (comma-separated in the env). */
    val checkoutOrigins: List<String> =
        checkoutOrigins.split(",").map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    /**
     * Mollie's card limit per transaction for this account (whole EUR; 10,000 since 17.10.2025). A single amount above
     * it is never sent to Mollie: /pay rejects it, booking hides the card option (bank transfer only).
     */
    val cardMaxAmountMinor: Long = cardMaxAmount * CENTS_PER_EUR

    val testMode: Boolean = this.apiKey.startsWith("test_")

    val customPayment =
        CustomPayment(
            minAmountMinor = customPaymentMinAmountMinor,
            maxAmountMinor = cardMaxAmountMinor,
            orderTtl = customPaymentOrderTtl,
            termsVersion = customPaymentTermsVersion.trim(),
            linkKeys = customPaymentLinkKeys.trim(),
        )

    private val testKeyInProd: Boolean = testMode && environment.acceptsProfiles(Profiles.of("prod"))

    val enabled: Boolean = this.apiKey.isNotEmpty() && !testKeyInProd

    init {
        if (testKeyInProd) log.error("Mollie DISABLED: the prod profile holds a Mollie TEST key")
        log.info(
            "Mollie: enabled={}, mode={}, webhook={}, reconcile={}, card-max={} EUR",
            enabled,
            if (this.apiKey.isEmpty()) "-" else if (testMode) "test" else "live",
            if (this.webhookUrl == null) "none" else "set",
            reconcileEnabled,
            cardMax(),
        )
    }

    private fun cardMax(): Long = cardMaxAmountMinor / CENTS_PER_EUR

    /** `application.mollie.custom-payment.*` (secret `/pay/<key>` link). Plain class: [linkKeys] are secrets too. */
    class CustomPayment(
        /** Smallest accepted amount in cents (5000 = 50 EUR). */
        val minAmountMinor: Long,
        /** Largest accepted amount in cents = the Mollie card limit. */
        val maxAmountMinor: Long,
        /** How long an open Mollie checkout is reused for the same data (dedupe window = 5/6 of it). */
        val orderTtl: Duration,
        val termsVersion: String,
        /** Raw `site=key[,key];site2=key` list; parsed by the custom payment feature. */
        val linkKeys: String,
    )

    private companion object {
        const val CENTS_PER_EUR = 100L
    }
}
