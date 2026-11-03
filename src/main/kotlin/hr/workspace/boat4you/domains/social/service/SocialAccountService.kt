package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.social.connectors.ConnectedAccount
import hr.workspace.boat4you.domains.social.connectors.PublishException
import hr.workspace.boat4you.domains.social.connectors.SocialConnector
import hr.workspace.boat4you.domains.social.dto.SocialAccountDto
import hr.workspace.boat4you.domains.social.jpa.SocialAccount
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialOAuthState
import hr.workspace.boat4you.domains.social.jpa.SocialOAuthStateRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPostTargetRepository
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Channels: list, connect (OAuth start + callback, or Bluesky credentials), assign a site, disconnect. One login can
 * return several channels (Meta: every page we manage and its Instagram), each upserted by (platform, network id) with
 * its token encrypted. A new channel gets the site the admin connected "for", when the login returned exactly one
 * channel, else the site its name points to (SiteGuess); the admin can change it on the Channels tab.
 */
@Service
class SocialAccountService(
    private val connectors: List<SocialConnector>,
    private val accounts: SocialAccountRepository,
    private val states: SocialOAuthStateRepository,
    private val targets: SocialPostTargetRepository,
    private val posts: SocialPostRepository,
    private val crypto: SocialCrypto,
    private val props: SocialProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun connectorFor(platform: SocialPlatform): SocialConnector? = connectors.firstOrNull { platform in it.platforms }

    @Transactional(readOnly = true)
    fun list(): List<SocialAccountDto> =
        accounts
            .findAllByDisconnectedAtIsNull()
            .sortedWith(compareBy({ it.platform?.ordinal }, { SiteIds.ALL.indexOf(it.site).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name.lowercase() }))
            .map { toDto(it) }

    /** Which networks can be connected on this server, and why not. */
    fun availability(): Map<SocialPlatform, String?> =
        SocialPlatform.entries.associateWith { p ->
            val c = connectorFor(p)
            when {
                c == null -> "Not available yet"
                !crypto.configured -> "SOCIAL_TOKEN_KEY is not set"
                else -> c.notConfiguredReason()
            }
        }

    @Transactional
    fun startConnect(
        platform: SocialPlatform,
        site: String?,
        returnUrl: String,
        adminUserId: Long,
    ): String {
        val connector = usable(platform)
        if (connector.credentialLogin) throw SocialValidationException("${platform.name} connects with a handle and app password")
        val back = allowedReturnUrl(returnUrl)
        states.deleteOlderThan(Instant.now().minus(STATE_TTL))
        val verifier = if (connector.usesPkce) SocialCrypto.randomToken(PKCE_VERIFIER_LENGTH) else null
        val state =
            states.save(
                SocialOAuthState().apply {
                    state = SocialCrypto.randomToken(STATE_LENGTH)
                    this.platform = platform
                    this.site = site?.takeIf { SiteIds.isKnown(it) }
                    this.adminUserId = adminUserId
                    codeVerifier = verifier
                    this.returnUrl = back
                },
            )
        return connector.authorizeUrl(state.state!!, verifier?.let { SocialCrypto.pkceChallenge(it) })
    }

    /**
     * The network sent the browser back. Always answers a URL of the admin to redirect to: `?connected=…&count=…` or
     * `?error=…&platform=…` (the admin shows a toast). An unknown or stale state gives the first admin origin.
     */
    @Transactional
    fun finishConnect(
        callbackPath: String,
        code: String?,
        stateValue: String?,
        error: String?,
    ): String {
        val state = stateValue?.let { states.findById(it).orElse(null) }
        val fallback = props.adminOriginList.firstOrNull()?.let { "$it/social/channels" } ?: "/"
        if (state == null) return withParams(fallback, mapOf("error" to "The connection expired; start it again"))
        states.delete(state)
        val platform = state.platform!!
        val back = state.returnUrl

        fun fail(message: String) = withParams(back, mapOf("error" to message.take(ERROR_MAX), "platform" to platform.name))
        if (state.createdAt.isBefore(Instant.now().minus(STATE_TTL))) return fail("The connection expired; start it again")
        if (callbackPathOf(platform) != callbackPath) return fail("Wrong network")
        if (!error.isNullOrBlank()) return fail(error)
        if (code.isNullOrBlank()) return fail("The network sent no code")
        return try {
            val connected = usable(platform).exchange(code, state.codeVerifier)
            if (connected.isEmpty()) return fail("No page or profile on this login")
            val saved = save(connected, state.site, state.adminUserId)
            withParams(back, mapOf("connected" to platform.name, "count" to saved.size.toString()))
        } catch (e: PublishException) {
            log.warn("Social connect {} refused: {}", platform, e.message)
            fail(e.message ?: "refused")
        } catch (e: SocialConfigException) {
            fail(e.message ?: "not configured")
        }
    }

    @Transactional
    fun connectWithCredentials(
        platform: SocialPlatform,
        identifier: String,
        secret: String,
        site: String?,
        adminUserId: Long,
    ): List<SocialAccountDto> {
        val connector = usable(platform)
        if (!connector.credentialLogin) throw SocialValidationException("${platform.name} connects through its login page")
        if (identifier.isBlank() || secret.isBlank()) throw SocialValidationException("Handle and app password are required")
        val connected =
            try {
                connector.connectWithCredentials(identifier, secret)
            } catch (e: PublishException) {
                throw SocialValidationException(e.message ?: "Login refused")
            }
        return save(connected, site?.takeIf { SiteIds.isKnown(it) }, adminUserId).map { toDto(it) }
    }

    @Transactional
    fun setSite(
        id: Long,
        site: String?,
    ): SocialAccountDto {
        val account = accounts.findById(id).orElseThrow { NoSuchElementException("Channel $id") }
        val value = site?.trim()?.ifBlank { null }
        if (value != null && !SiteIds.isKnown(value)) throw SocialValidationException("Unknown site $value")
        account.site = value
        return toDto(accounts.save(account))
    }

    /** Tokens wiped, hidden from the list; its scheduled channels fail with a clear reason. History stays. */
    @Transactional
    fun disconnect(id: Long) {
        val account = accounts.findById(id).orElseThrow { NoSuchElementException("Channel $id") }
        account.accessTokenEnc = null
        account.refreshTokenEnc = null
        account.disconnectedAt = Instant.now()
        account.status = SocialAccountStatus.EXPIRED
        account.lastError = "Disconnected in the admin"
        accounts.save(account)
        targets.findAllByAccount_IdAndStatus(id, SocialTargetStatus.SCHEDULED).forEach { t ->
            t.status = SocialTargetStatus.FAILED
            t.error = "The channel was disconnected"
            t.dueAt = null
            targets.save(t)
            t.post?.let { p ->
                p.status = SocialPostService.deriveStatus(p.targets.map { it.status }, p.status)
                posts.save(p)
            }
        }
    }

    private fun save(
        connected: List<ConnectedAccount>,
        chosenSite: String?,
        adminUserId: Long?,
    ): List<SocialAccount> =
        connected.map { c ->
            val existing = accounts.findByPlatformAndExternalId(c.platform, c.externalId)
            val account =
                existing ?: SocialAccount().apply {
                    platform = c.platform
                    externalId = c.externalId
                }
            account.name = c.name.take(NAME_MAX)
            account.handle = c.handle?.take(HANDLE_MAX)
            account.avatarUrl = c.avatarUrl?.take(URL_MAX)
            account.accessTokenEnc = crypto.encrypt(c.tokens.accessToken)
            account.refreshTokenEnc = c.tokens.refreshToken?.let { crypto.encrypt(it) }
            account.tokenExpiresAt = c.tokens.expiresAt
            account.meta = c.meta.toMutableMap()
            account.privateOnly = c.privateOnly
            account.status = SocialAccountStatus.CONNECTED
            account.lastError = null
            account.disconnectedAt = null
            account.connectedBy = adminUserId
            account.connectedAt = Instant.now()
            if (existing == null || account.site == null) {
                account.site =
                    when {
                        c.platform == SocialPlatform.YOUTUBE -> null
                        chosenSite != null && connected.size == 1 -> chosenSite
                        else -> SiteGuess.fromText("${c.name} ${c.handle.orEmpty()}") ?: chosenSite.takeIf { connected.size == 1 }
                    }
            }
            accounts.save(account)
        }

    private fun usable(platform: SocialPlatform): SocialConnector {
        val connector = connectorFor(platform) ?: throw SocialConfigException("${platform.name} is not available yet")
        if (!crypto.configured) throw SocialConfigException("SOCIAL_TOKEN_KEY is not set (32 random bytes, base64)")
        connector.notConfiguredReason()?.let { throw SocialConfigException(it) }
        return connector
    }

    /** The return address must be one of our admin origins (no open redirect). */
    private fun allowedReturnUrl(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: throw SocialValidationException("Bad return address")
        val origin = "${u.scheme}://${u.authority}"
        if (u.userInfo != null || props.adminOriginList.none { it.equals(origin, ignoreCase = true) }) throw SocialValidationException("Return address not allowed")
        return url.trim().substringBefore('?').substringBefore('#')
    }

    fun toDto(a: SocialAccount): SocialAccountDto =
        SocialAccountDto(
            id = a.id!!,
            platform = a.platform!!,
            site = a.site,
            name = a.name,
            handle = a.handle,
            avatarUrl = a.avatarUrl,
            status = a.status,
            tokenExpiresAt = if (a.platform == SocialPlatform.TIKTOK || a.platform == SocialPlatform.YOUTUBE) null else a.tokenExpiresAt,
            lastError = a.lastError,
            connectedAt = a.connectedAt,
            privateOnly = a.privateOnly,
        )

    companion object {
        private val STATE_TTL: Duration = Duration.ofMinutes(10)
        private const val STATE_LENGTH = 43
        private const val PKCE_VERIFIER_LENGTH = 64
        private const val ERROR_MAX = 200
        private const val NAME_MAX = 200
        private const val HANDLE_MAX = 300
        private const val URL_MAX = 1000

        /** The path segment of the OAuth callback per network (Meta covers Facebook and Instagram). */
        fun callbackPathOf(platform: SocialPlatform): String =
            when (platform) {
                SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM -> "meta"
                else -> platform.name.lowercase(Locale.ROOT).replace('_', '-')
            }

        fun withParams(
            url: String,
            params: Map<String, String>,
        ): String = UriComponentsBuilder.fromUriString(url).apply { params.forEach { (k, v) -> queryParam(k, v) } }.encode().build().toUriString()
    }
}

/** Which of our seven sites a page / profile name points to ("Catamaran Charter Greece" → catamaran-greece). */
object SiteGuess {
    fun fromText(text: String): String? {
        val t = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ")
        return when {
            "greece" in t -> SiteIds.CATAMARAN_GREECE
            "italy" in t -> SiteIds.CATAMARAN_ITALY
            "caribbean" in t -> SiteIds.CATAMARAN_CARIBBEAN
            "europe yachts" in t || "europeyachts" in t -> SiteIds.EUROPE_YACHTS
            "croatia yachting" in t || "croatiayachting" in t -> SiteIds.CROATIA_YACHTING
            "catamaran" in t && "croatia" in t -> SiteIds.CATAMARAN_CROATIA
            "boat4you" in t || "boat 4 you" in t -> SiteIds.BOAT4YOU
            else -> null
        }
    }
}
