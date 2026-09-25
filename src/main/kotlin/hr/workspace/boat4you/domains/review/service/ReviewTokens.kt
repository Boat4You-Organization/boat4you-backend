package hr.workspace.boat4you.domains.review.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Magic-link tokens for the guest review form. The raw token (32 random bytes, URL-safe base64 without padding =
 * 43 characters) only ever exists in the e-mail; the database keeps its SHA-256 hex, so a leaked table does not
 * yield working links. A plain (unsalted) hash is enough because the input is 256 bits of randomness, not a
 * password.
 */
object ReviewTokens {
    const val TOKEN_BYTES = 32

    /** How long a review link works after it was sent. */
    val VALIDITY: Duration = Duration.ofDays(60)

    /** How long a submitted review can still be changed through the same link. */
    val EDIT_WINDOW: Duration = Duration.ofHours(24)

    private val random = SecureRandom()
    private val WELL_FORMED = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }

    /** Cheap shape check before any database read: anything else cannot be one of our tokens. */
    fun isWellFormed(token: String): Boolean = WELL_FORMED.matches(token)

    fun isExpired(
        expiresAt: Instant,
        now: Instant,
    ): Boolean = !now.isBefore(expiresAt)

    fun editableUntil(createdAt: Instant): Instant = createdAt.plus(EDIT_WINDOW)

    fun isEditable(
        createdAt: Instant,
        now: Instant,
    ): Boolean = now.isBefore(editableUntil(createdAt))
}
