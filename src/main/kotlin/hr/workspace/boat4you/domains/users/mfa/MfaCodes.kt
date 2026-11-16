package hr.workspace.boat4you.domains.users.mfa

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Codes for two-step sign-in without extra libraries (Mario 16.11.2026):
 * - TOTP as Google Authenticator / Authy compute it (RFC 6238: HMAC-SHA1, 30-second steps, 6 digits);
 * - base32 for the authenticator secret (RFC 4648, no padding);
 * - one-time recovery codes and device tokens, stored as SHA-256 only.
 */
object MfaCodes {
    private val random = SecureRandom()
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val STEP_SECONDS = 30L

    /** Codes of the previous and the next step are accepted too: phones run a few seconds off. */
    private const val WINDOW = 1

    /** No 0/o, 1/l/i: a recovery code is read off paper. */
    private const val RECOVERY_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun fromBase32(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = BASE32.indexOf(c)
            require(value >= 0) { "not base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** A new authenticator secret: 160 bits, as RFC 4226 recommends. */
    fun newTotpSecret(): String = base32(randomBytes(20))

    fun totpAt(
        secret: String,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(fromBase32(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    fun currentStep(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Long = nowEpochSeconds / STEP_SECONDS

    /**
     * The step the 6-digit [code] belongs to, if it is valid now and newer than [lastStep] (a code is used once);
     * null otherwise. Constant-time comparison.
     */
    fun matchTotp(
        secret: String,
        code: String,
        lastStep: Long?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Long? {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) return null
        val now = currentStep(nowEpochSeconds)
        return (now - WINDOW..now + WINDOW).firstOrNull { step ->
            (lastStep == null || step > lastStep) &&
                MessageDigest.isEqual(totpAt(secret, step).toByteArray(), digits.toByteArray())
        }
    }

    /** otpauth:// link for the QR code (Google Authenticator, Authy, 1Password, iOS Passwords). */
    fun otpauthUri(
        secret: String,
        account: String,
        issuer: String = "Boat4You Admin",
    ): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    /** "xxxxx-xxxxx" from an unambiguous alphabet (about 49 bits). */
    fun newRecoveryCode(): String {
        val chars = (1..10).map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("")
        return chars.substring(0, 5) + "-" + chars.substring(5)
    }

    /** Lower case, without spaces and dashes: how a typed recovery code is compared. */
    fun normaliseRecoveryCode(code: String): String = code.lowercase().filter { it.isLetterOrDigit() }
}
