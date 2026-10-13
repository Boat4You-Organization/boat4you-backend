package hr.workspace.boat4you.domains.payment.revolut

import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fake, test-only values — never real Revolut credentials. */
internal const val TEST_BASE_URL = "https://sandbox-merchant.revolut.com"
internal const val TEST_SECRET_KEY = "sk_test_not_a_real_key"
internal const val TEST_WEBHOOK_SECRET = "wsk_test_not_a_real_secret"

internal fun revolutProperties(
    baseUrl: String = TEST_BASE_URL,
    secretKey: String = TEST_SECRET_KEY,
    webhookSecret: String = TEST_WEBHOOK_SECRET,
    profiles: List<String> = emptyList(),
    checkoutOrigins: String = "https://checkout.revolut.com",
    linkKeys: String = "",
): RevolutProperties =
    RevolutProperties(
        baseUrl = baseUrl,
        secretKey = secretKey,
        webhookSecret = webhookSecret,
        reconcileEnabled = false,
        checkoutOrigins = checkoutOrigins,
        customPaymentMinAmountMinor = 5000,
        customPaymentMaxAmount = 100_000,
        customPaymentOrderTtl = Duration.ofHours(1),
        customPaymentTermsVersion = "2026-10-08",
        customPaymentLinkKeys = linkKeys,
        environment = MockEnvironment().apply { setActiveProfiles(*profiles.toTypedArray()) },
    )

/** Independent oracle for the Revolut signature: hex HMAC-SHA256 of `"v1.<ts>.<body>"`. */
internal fun revolutSignatureHex(
    secret: String,
    timestamp: String,
    body: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val payload = "v1.$timestamp.".toByteArray(Charsets.UTF_8) + body
    return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
}
