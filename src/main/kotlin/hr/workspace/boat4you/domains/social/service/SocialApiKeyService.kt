package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.social.dto.SocialApiKeyDto
import hr.workspace.boat4you.domains.social.jpa.SocialApiKey
import hr.workspace.boat4you.domains.social.jpa.SocialApiKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keys for the automation API (`/api/social/v1`, header `X-Api-Key`): `b4ys_` + 40 random characters, shown once;
 * only the SHA-256 and the first 9 characters are stored. A drafts-only key can never schedule or publish. 60
 * requests per hour per key (this node).
 */
@Service
class SocialApiKeyService(
    private val keys: SocialApiKeyRepository,
) {
    private val windows = ConcurrentHashMap<Long, Pair<Long, Int>>()

    @Transactional(readOnly = true)
    fun list(): List<SocialApiKeyDto> = keys.findAllByRevokedAtIsNullOrderByCreatedAtDesc().map { toDto(it) }

    @Transactional
    fun create(
        name: String,
        draftsOnly: Boolean,
        by: Long?,
    ): SocialApiKeyDto {
        val clean = name.trim().take(NAME_MAX).ifBlank { throw SocialValidationException("Name the key") }
        val secret = PREFIX + SocialCrypto.randomToken(SECRET_LENGTH)
        val saved =
            keys.save(
                SocialApiKey().apply {
                    this.name = clean
                    prefix = secret.take(PREFIX_SHOWN)
                    secretSha256 = SocialCrypto.sha256Hex(secret)
                    this.draftsOnly = draftsOnly
                    createdBy = by
                },
            )
        return toDto(saved).copy(secret = secret)
    }

    @Transactional
    fun revoke(id: Long) {
        val key = keys.findById(id).orElseThrow { NoSuchElementException("Key $id") }
        key.revokedAt = Instant.now()
        keys.save(key)
    }

    /** The key for this secret, or null (unknown, revoked or over the hourly limit). Marks it used. */
    @Transactional
    fun authenticate(secret: String?): SocialApiKey? {
        if (secret.isNullOrBlank() || !secret.startsWith(PREFIX)) return null
        val key = keys.findBySecretSha256AndRevokedAtIsNull(SocialCrypto.sha256Hex(secret.trim())) ?: return null
        if (!withinLimit(key.id!!)) throw SocialRateLimitException()
        key.lastUsedAt = Instant.now()
        return keys.save(key)
    }

    private fun withinLimit(id: Long): Boolean {
        val hour = Instant.now().epochSecond / HOUR_S
        val (h, n) = windows.compute(id) { _, old -> if (old == null || old.first != hour) hour to 1 else old.first to old.second + 1 }!!
        return h == hour && n <= LIMIT_PER_HOUR
    }

    private fun toDto(k: SocialApiKey) = SocialApiKeyDto(k.id!!, k.name, k.prefix, k.createdAt, k.lastUsedAt, k.draftsOnly)

    companion object {
        const val PREFIX = "b4ys_"
        private const val SECRET_LENGTH = 40
        private const val PREFIX_SHOWN = 9
        private const val NAME_MAX = 80
        private const val HOUR_S = 3600L
        private const val LIMIT_PER_HOUR = 60
    }
}

class SocialRateLimitException : RuntimeException("Too many requests for this key; try again in an hour")
