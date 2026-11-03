package hr.workspace.boat4you.domains.social.connectors

import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import java.nio.file.Path
import java.time.Instant

/**
 * One network (or one login that yields several: Meta = Facebook pages + their Instagram accounts). The backend owns
 * the developer app; [authorizeUrl] / [exchange] run the OAuth code flow, [publish] sends one post to one account.
 * Implementations never log tokens or bodies with personal data.
 */
interface SocialConnector {
    /** The platforms this connector publishes to. */
    val platforms: Set<SocialPlatform>

    /** Networks connected with a handle + secret (Bluesky) instead of an OAuth redirect. */
    val credentialLogin: Boolean get() = false

    /** Why it cannot be used on this server (missing app id / secret), or null when it can. */
    fun notConfiguredReason(): String?

    /** Consent page of the network; [codeChallenge] is the PKCE S256 challenge when [usesPkce]. */
    fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String = throw UnsupportedOperationException("No OAuth for ${platforms.joinToString()}")

    val usesPkce: Boolean get() = false

    /** Code → the accounts this login may publish to, with their tokens. */
    fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> = throw UnsupportedOperationException("No OAuth for ${platforms.joinToString()}")

    fun connectWithCredentials(
        identifier: String,
        secret: String,
    ): List<ConnectedAccount> = throw UnsupportedOperationException("No credential login for ${platforms.joinToString()}")

    /** A token that is about to expire, refreshed; null = nothing to do. */
    fun refresh(tokens: Tokens): Tokens? = null

    fun publish(job: PublishJob): PublishResult
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
)

data class ConnectedAccount(
    val platform: SocialPlatform,
    val externalId: String,
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val tokens: Tokens,
    val meta: Map<String, String> = emptyMap(),
    val privateOnly: Boolean = false,
)

data class PublishMedia(
    val kind: SocialMediaKind,
    /** Public https URL the network can fetch (Instagram, Threads, Facebook, TikTok photos). */
    val url: String,
    val file: Path,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    /** The photo as JPEG of at most n bytes (Bluesky). */
    val jpegAtMost: (Int) -> ByteArray,
)

data class PublishJob(
    val platform: SocialPlatform,
    val externalId: String,
    val tokens: Tokens,
    val meta: Map<String, String>,
    val privateOnly: Boolean,
    val text: String,
    val title: String?,
    val link: String?,
    val media: List<PublishMedia>,
)

data class PublishResult(
    val externalId: String,
    val url: String?,
)

/**
 * The network refused or failed. [retryable]: try again later (5xx, 429, still processing). [tokenInvalid]: the account
 * must be connected again. The message is the network's own words, trimmed, and is shown to the admin.
 */
class PublishException(
    message: String,
    val retryable: Boolean = false,
    val tokenInvalid: Boolean = false,
) : RuntimeException(message.take(MAX_MESSAGE)) {
    private companion object {
        const val MAX_MESSAGE = 900
    }
}
