package hr.workspace.boat4you.domains.payment.revolut

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

/**
 * `application.revolut.*` — Revolut Merchant API settings shared by the secret `/pay/<key>` custom payments and
 * (later) the online-booking card payments. Every key has a blank / safe default so a node without the env vars
 * (cusma3, local dev) keeps starting: the feature then reports itself disabled instead of failing the boot.
 *
 * [enabled] only when base-url, secret-key and webhook-secret are all set, and never when the `prod` profile points
 * at the sandbox host (ERROR at startup — real customers must not be sent to a test checkout). Not a data class on
 * purpose: a generated toString() would print the secret key.
 */
@Component
class RevolutProperties(
    @Value("\${application.revolut.base-url:}") baseUrl: String,
    @Value("\${application.revolut.secret-key:}") secretKey: String,
    @Value("\${application.revolut.webhook-secret:}") webhookSecret: String,
    @Value("\${application.revolut.reconcile-enabled:false}") val reconcileEnabled: Boolean,
    @Value("\${application.revolut.checkout-origins:https://checkout.revolut.com}") checkoutOrigins: String,
    @Value("\${application.revolut.custom-payment.min-amount-minor:5000}") customPaymentMinAmountMinor: Long,
    @Value("\${application.revolut.custom-payment.max-amount:100000}") customPaymentMaxAmount: Long,
    @Value("\${application.revolut.custom-payment.order-ttl:PT1H}") customPaymentOrderTtl: Duration,
    @Value("\${application.revolut.custom-payment.terms-version:2026-10-08}") customPaymentTermsVersion: String,
    @Value("\${application.revolut.custom-payment.link-keys:}") customPaymentLinkKeys: String,
    environment: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Merchant API root without a trailing slash, e.g. `https://merchant.revolut.com`. */
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    /** Bearer secret for the Merchant API. Never logged. */
    val secretKey: String = secretKey.trim()

    /** Signing secret of the registered webhook (`wsk_…`). Never logged. */
    val webhookSecret: String = webhookSecret.trim()

    /** Origins a `checkout_url` returned by Revolut may have (comma-separated in the env). */
    val checkoutOrigins: List<String> =
        checkoutOrigins.split(",").map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    val customPayment =
        CustomPayment(
            minAmountMinor = customPaymentMinAmountMinor,
            maxAmount = customPaymentMaxAmount,
            orderTtl = customPaymentOrderTtl,
            termsVersion = customPaymentTermsVersion.trim(),
            linkKeys = customPaymentLinkKeys.trim(),
        )

    private val missingKeys: List<String> =
        listOfNotNull(
            "base-url".takeIf { this.baseUrl.isEmpty() },
            "secret-key".takeIf { this.secretKey.isEmpty() },
            "webhook-secret".takeIf { this.webhookSecret.isEmpty() },
        )

    private val sandboxInProd: Boolean =
        this.baseUrl.contains("sandbox", ignoreCase = true) && environment.acceptsProfiles(Profiles.of("prod"))

    val enabled: Boolean = missingKeys.isEmpty() && !sandboxInProd

    init {
        if (sandboxInProd) {
            log.error("Revolut Merchant API DISABLED: the prod profile is pointed at a sandbox base-url ({})", host())
        }
        log.info(
            "Revolut Merchant API: enabled={}, host={}, api-version={}, reconcile={}, missing={}",
            enabled,
            host(),
            RevolutMerchantClient.API_VERSION,
            reconcileEnabled,
            missingKeys.ifEmpty { "none" },
        )
    }

    private fun host(): String = runCatching { URI(baseUrl).host }.getOrNull() ?: "-"

    /** `application.revolut.custom-payment.*` (secret `/pay/<key>` link). Plain class: [linkKeys] are secrets too. */
    class CustomPayment(
        /** Smallest accepted amount in cents (5000 = 50 EUR). */
        val minAmountMinor: Long,
        /** Largest accepted amount in whole EUR. */
        val maxAmount: Long,
        /** `expire_pending_after` of a created order. */
        val orderTtl: Duration,
        val termsVersion: String,
        /** Raw `site=key[,key];site2=key` list; parsed by the custom payment feature. */
        val linkKeys: String,
    ) {
        val maxAmountMinor: Long get() = maxAmount * CENTS_PER_EUR
    }

    private companion object {
        const val CENTS_PER_EUR = 100L
    }
}
