package hr.workspace.boat4you.domains.payment.mollie

import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/** Fake, test-only values — never real Mollie credentials. */
internal const val TEST_BASE_URL = "https://api.mollie.test"
internal const val TEST_API_KEY = "test_notarealmollieapikey0000000000"
internal const val LIVE_API_KEY = "live_notarealmollieapikey0000000000"

internal fun mollieProperties(
    baseUrl: String = TEST_BASE_URL,
    apiKey: String = LIVE_API_KEY,
    webhookUrl: String = "https://api.boat4you.com/webhooks/mollie",
    profiles: List<String> = emptyList(),
    checkoutOrigins: String = "https://www.mollie.com",
    cardMaxAmount: Long = 10_000,
    linkKeys: String = "",
    returnBaseUrl: String = "",
): MollieProperties =
    MollieProperties(
        baseUrl = baseUrl,
        apiKey = apiKey,
        webhookUrl = webhookUrl,
        reconcileEnabled = false,
        checkoutOrigins = checkoutOrigins,
        cardMaxAmount = cardMaxAmount,
        customPaymentMinAmountMinor = 5000,
        customPaymentOrderTtl = Duration.ofMinutes(15),
        customPaymentTermsVersion = "2026-11-10",
        customPaymentLinkKeys = linkKeys,
        customPaymentReturnBaseUrl = returnBaseUrl,
        environment = MockEnvironment().apply { setActiveProfiles(*profiles.toTypedArray()) },
    )

/** Klarna settings over [mollie] (enabled by default in tests; the account maximum is 10 000 EUR as in production). */
internal fun klarnaProperties(
    enabled: Boolean = true,
    surchargePercent: String = "",
    mollie: MollieProperties = mollieProperties(),
): MollieKlarnaProperties = MollieKlarnaProperties(enabled, 10_000, surchargePercent, mollie)
