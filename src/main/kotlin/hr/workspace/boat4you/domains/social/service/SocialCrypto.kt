package hr.workspace.boat4you.domains.social.service

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for network tokens and app passwords at rest: `v1:` + base64(12-byte IV ‖ ciphertext ‖ tag). The key is
 * `SOCIAL_TOKEN_KEY` (base64 of 32 bytes); a blank or short key makes [configured] false and every encrypt refuse, so a
 * token is never stored in the clear. Plus the random tokens and hashes the rest of Objave needs.
 */
@Component
class SocialCrypto(
    props: SocialProperties,
) {
    private val key: SecretKeySpec? =
        runCatching { Base64.getDecoder().decode(props.tokenKey.trim()) }
            .getOrNull()
            ?.takeIf { it.size == KEY_BYTES }
            ?.let { SecretKeySpec(it, "AES") }

    val configured: Boolean get() = key != null

    fun encrypt(plain: String): String {
        val k = key ?: throw SocialConfigException("SOCIAL_TOKEN_KEY is not set (32 random bytes, base64)")
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String {
        val k = key ?: throw SocialConfigException("SOCIAL_TOKEN_KEY is not set (32 random bytes, base64)")
        require(stored.startsWith(PREFIX)) { "Unknown token format" }
        val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            }
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }

    fun decryptOrNull(stored: String?): String? = stored?.let { decrypt(it) }

    companion object {
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "v1:"
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()

        /** URL-safe random string from [ALPHABET] (62 symbols, ~5.95 bits each). */
        fun randomToken(length: Int): String = buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** PKCE S256 challenge of a verifier. */
        fun pkceChallenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            )
    }
}

/** A network or Objave itself is not configured on this server (missing env): 409 with the reason. */
class SocialConfigException(
    message: String,
) : RuntimeException(message)

/** The request breaks a rule (unknown account, wrong status, empty post…): 400 with the reason. */
class SocialValidationException(
    message: String,
) : RuntimeException(message)

/** The post changed state since it was read (edit of a post already going out): 409. */
class SocialConflictException(
    message: String,
) : RuntimeException(message)
