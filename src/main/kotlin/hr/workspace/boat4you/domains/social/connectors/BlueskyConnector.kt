package hr.workspace.boat4you.domains.social.connectors

import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Bluesky (AT Protocol XRPC): handle + app password, no app to register. The app password is stored encrypted as the
 * account's token and a session is opened per publish. Up to 4 photos (each ≤ ~1 MB, shrunk here); links become
 * facets (UTF-8 byte offsets) so they are clickable. Video is not supported yet (it needs Bluesky's video service).
 */
@Component
class BlueskyConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()

    override val platforms = setOf(SocialPlatform.BLUESKY)

    override val credentialLogin = true

    override fun notConfiguredReason(): String? = null

    private val xrpc get() = "${props.blueskyService.trimEnd('/')}/xrpc"

    override fun connectWithCredentials(
        identifier: String,
        secret: String,
    ): List<ConnectedAccount> {
        val s = session(identifier.trim().removePrefix("@"), secret.trim())
        val profile = runCatching { http.get("$xrpc/app.bsky.actor.getProfile?actor=${s.did}", SocialHttp.bearer(s.jwt)) }.getOrNull()
        return listOf(
            ConnectedAccount(
                platform = SocialPlatform.BLUESKY,
                externalId = s.did,
                name = profile?.path("displayName")?.asText("")?.ifBlank { null } ?: s.handle,
                handle = "@${s.handle}",
                avatarUrl = profile?.path("avatar")?.asText("")?.ifBlank { null },
                tokens = Tokens(secret.trim(), null, null),
                meta = mapOf("handle" to s.handle, "identifier" to identifier.trim().removePrefix("@")),
            ),
        )
    }

    private data class Session(
        val did: String,
        val handle: String,
        val jwt: String,
    )

    private fun session(
        identifier: String,
        password: String,
    ): Session {
        val res = http.postJson("$xrpc/com.atproto.server.createSession", mapOf("identifier" to identifier, "password" to password)).body
        return Session(res.path("did").asText(), res.path("handle").asText(identifier), res.path("accessJwt").asText())
    }

    override fun publish(job: PublishJob): PublishResult {
        if (job.media.any { it.kind == SocialMediaKind.VIDEO }) throw PublishException("Bluesky video is not supported yet; use photos")
        val identifier = job.meta["identifier"] ?: job.meta["handle"] ?: job.externalId
        val s =
            try {
                session(identifier, job.tokens.accessToken)
            } catch (e: PublishException) {
                throw PublishException("Bluesky login refused: ${e.message}", tokenInvalid = !e.retryable, retryable = e.retryable)
            }
        val auth = SocialHttp.bearer(s.jwt)
        val text = if (job.link.isNullOrBlank() || job.text.contains(job.link)) job.text else "${job.text}\n${job.link}"
        val images =
            job.media.take(MAX_IMAGES).map { m ->
                val blob = http.postBytes("$xrpc/com.atproto.repo.uploadBlob", m.jpegAtMost(MAX_IMAGE_BYTES), "image/jpeg", auth).body.path("blob")
                buildMap<String, Any> {
                    put("alt", "")
                    put("image", http.json.convertValue(blob, Map::class.java))
                    if (m.width != null && m.height != null) put("aspectRatio", mapOf("width" to m.width, "height" to m.height))
                }
            }
        val record =
            buildMap<String, Any> {
                put("\$type", "app.bsky.feed.post")
                put("text", text)
                put("createdAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
                linkFacets(text).takeIf { it.isNotEmpty() }?.let { put("facets", it) }
                if (images.isNotEmpty()) put("embed", mapOf("\$type" to "app.bsky.embed.images", "images" to images))
            }
        val uri =
            pointOfNoReturn(BLUESKY) {
                http
                    .postJson("$xrpc/com.atproto.repo.createRecord", mapOf("repo" to s.did, "collection" to "app.bsky.feed.post", "record" to record), auth)
                    .body
                    .path("uri")
                    .asText()
                    .sentOr(BLUESKY)
            }
        return PublishResult(uri, "https://bsky.app/profile/${s.handle}/post/${uri.substringAfterLast('/')}")
    }

    companion object {
        private const val BLUESKY = "Bluesky"
        private const val MAX_IMAGES = 4
        private const val MAX_IMAGE_BYTES = 950_000
        private val URL = Regex("""https?://[^\s<>"]+""")

        /** Clickable links: byte offsets in the UTF-8 text, as AT Protocol counts them. */
        fun linkFacets(text: String): List<Map<String, Any>> =
            URL
                .findAll(text)
                .map { m ->
                    val url = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':')
                    val start = text.substring(0, m.range.first).toByteArray(Charsets.UTF_8).size
                    val end = start + url.toByteArray(Charsets.UTF_8).size
                    mapOf(
                        "index" to mapOf("byteStart" to start, "byteEnd" to end),
                        "features" to listOf(mapOf("\$type" to "app.bsky.richtext.facet#link", "uri" to url)),
                    )
                }.toList()
    }
}
