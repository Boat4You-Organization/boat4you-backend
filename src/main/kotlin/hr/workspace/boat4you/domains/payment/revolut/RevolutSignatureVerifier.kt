package hr.workspace.boat4you.domains.payment.revolut

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Revolut webhook signature check (pure; no I/O). Revolut sends
 * - `Revolut-Request-Timestamp`: milliseconds since the epoch,
 * - `Revolut-Signature`: one or several comma-separated `v1=<hex>` entries (several while a secret is rotated),
 *
 * where `<hex>` = HMAC-SHA256(webhook secret, `"v1." + timestamp + "." + raw body`). Valid when the timestamp is
 * within [TOLERANCE_MS] of now and ANY `v1` entry matches (constant-time compare). Missing or malformed headers,
 * a blank secret or a seconds-valued timestamp are simply invalid.
 */
@Component
class RevolutSignatureVerifier(
    private val clock: Clock,
) {
    @Autowired
    constructor() : this(Clock.systemUTC())

    fun isValid(
        timestampHeader: String?,
        signatureHeader: String?,
        rawBody: ByteArray,
        secret: String,
    ): Boolean {
        if (secret.isBlank()) return false
        val timestamp = timestampHeader?.trim().orEmpty()
        if (!TIMESTAMP.matches(timestamp)) return false
        val timestampMs = timestamp.toLongOrNull() ?: return false
        if (abs(clock.millis() - timestampMs) > TOLERANCE_MS) return false

        val candidates =
            signatureHeader
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(VERSION_PREFIX) }
                ?.mapNotNull { decodeHex(it.removePrefix(VERSION_PREFIX)) }
                .orEmpty()
        if (candidates.isEmpty()) return false

        val expected = sign(secret, timestamp, rawBody)
        // Check every candidate (no early exit) so the time does not depend on which entry matched.
        var matched = false
        for (candidate in candidates) {
            if (MessageDigest.isEqual(expected, candidate)) matched = true
        }
        return matched
    }

    companion object {
        const val TIMESTAMP_HEADER = "Revolut-Request-Timestamp"
        const val SIGNATURE_HEADER = "Revolut-Signature"

        /** Allowed clock skew / replay window: 5 minutes. */
        const val TOLERANCE_MS = 300_000L

        private const val VERSION_PREFIX = "v1="
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val SHA256_HEX_LENGTH = 64
        private val TIMESTAMP = Regex("^[0-9]{1,18}$")
        private val HEX = Regex("^[0-9a-fA-F]+$")

        /** HMAC-SHA256 over the bytes of `"v1.<timestamp>."` followed by the raw body bytes. */
        fun sign(
            secret: String,
            timestamp: String,
            rawBody: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
            mac.update("v1.$timestamp.".toByteArray(Charsets.UTF_8))
            return mac.doFinal(rawBody)
        }

        private fun decodeHex(hex: String): ByteArray? = if (hex.length == SHA256_HEX_LENGTH && HEX.matches(hex)) HexFormat.of().parseHex(hex) else null
    }
}
