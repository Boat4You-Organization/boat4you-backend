package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Handoff tokens (design 2.5): the one-time pass that lets a visitor who moves from one of our sites to a sister site keep the same
 * visitor key. A token is 32 random bytes, base64url (43 chars), valid for [TTL] and for one presentation only, and it names the one site it
 * may be presented on. Nothing is persisted: a restart simply invalidates the tokens in flight, and the visitor starts a fresh visitor key.
 * At most [MAX_TOKENS] are held; when full, expired ones go first, else [issue] refuses.
 */
@Component
class HandoffTokenStore {
    internal var clock: Clock = Clock.systemUTC()

    /** What a token stands for: the visitor key to adopt on [toSiteId], and the site the visitor came from. */
    data class Grant(
        val visitorKey: String,
        val fromSiteId: String,
        val toSiteId: String,
        val expiresAt: Instant,
    )

    private val grants = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()

    /** A new token for [visitorKey] moving from [fromSiteId] to [toSiteId], or null when [MAX_TOKENS] valid tokens are already out. */
    @Synchronized
    fun issue(
        visitorKey: String,
        fromSiteId: String,
        toSiteId: String,
    ): String? {
        if (grants.size >= MAX_TOKENS) purgeExpired()
        if (grants.size >= MAX_TOKENS) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        grants[token] = Grant(visitorKey, fromSiteId, toSiteId, clock.instant().plus(TTL))
        return token
    }

    /**
     * The grant behind [token] if it is presented on the site it was issued for before it expires, else null. A token of the right format
     * is removed before any other check, so a wrong site or a late presentation burns it as well.
     */
    fun consume(
        token: String?,
        toSiteId: String,
    ): Grant? {
        val grant = token?.takeIf { TOKEN_FORMAT.matches(it) }?.let { grants.remove(it) }
        return grant?.takeIf { it.toSiteId == toSiteId && clock.instant().isBefore(it.expiresAt) }
    }

    /** Drops the tokens past their expiry. Returns how many. */
    @Scheduled(fixedDelay = 30_000)
    fun purgeExpired(): Int {
        val now = clock.instant()
        var removed = 0
        for ((token, grant) in grants) if (!now.isBefore(grant.expiresAt) && grants.remove(token, grant)) removed++
        return removed
    }

    fun size(): Int = grants.size

    companion object {
        val TTL: Duration = Duration.ofMinutes(2)
        const val MAX_TOKENS = 10_000
        val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")
        private const val TOKEN_BYTES = 32
    }
}
