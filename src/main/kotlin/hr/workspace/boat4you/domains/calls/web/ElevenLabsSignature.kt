package hr.workspace.boat4you.domains.calls.web

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The ElevenLabs webhook signature (same check as the official SDK's construct_event, 30.10.2026): header
 * `ElevenLabs-Signature: t=<unix seconds>,v0=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`, and `t` at most 30 minutes old
 * (or ahead). Compared in constant time; an empty secret never verifies.
 */
object ElevenLabsSignature {
    private val TOLERANCE: Duration = Duration.ofMinutes(30)

    fun verify(
        header: String?,
        rawBody: String,
        secret: String,
        now: Instant,
    ): Boolean {
        if (header.isNullOrBlank() || secret.isBlank()) return false
        val parts = header.split(',').map { it.trim() }
        val t = parts.firstOrNull { it.startsWith("t=") }?.substring(2)?.toLongOrNull() ?: return false
        val v0 = parts.firstOrNull { it.startsWith("v0=") } ?: return false
        if (Duration.between(Instant.ofEpochSecond(t), now).abs() > TOLERANCE) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val expected = "v0=" + mac.doFinal("$t.$rawBody".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), v0.toByteArray(Charsets.UTF_8))
    }
}
