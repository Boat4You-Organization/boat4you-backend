package hr.workspace.boat4you.domains.social.connectors

import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.bearer
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.file.Files
import java.time.Instant

/**
 * LinkedIn Posts API (versioned REST). The login gives the member (always) and, when our app has the Community
 * Management API (`w_organization_social`, `rw_organization_admin` in `social.linkedin.scopes`), every company page the
 * member administers. Photos: `images?action=initializeUpload` → PUT; video: `videos?action=initializeUpload` → PUT the
 * parts → `finalizeUpload`. Tokens live 60 days; without refresh tokens the admin reconnects.
 */
@Component
class LinkedInConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()

    override val platforms = setOf(SocialPlatform.LINKEDIN)

    override fun notConfiguredReason(): String? =
        if (props.linkedinClientId.isBlank() || props.linkedinClientSecret.isBlank()) "LinkedIn app not configured (SOCIAL_LINKEDIN_CLIENT_ID / SECRET)" else null

    private val redirectUri get() = props.callbackUrl("linkedin")

    private fun rest(token: String) = bearer(token) + mapOf("LinkedIn-Version" to props.linkedinVersion, "X-Restli-Protocol-Version" to "2.0.0")

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://www.linkedin.com/oauth/v2/authorization",
            mapOf(
                "response_type" to "code",
                "client_id" to props.linkedinClientId,
                "redirect_uri" to redirectUri,
                "state" to state,
                "scope" to props.linkedinScopes,
            ),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val t =
            http.postForm(
                "https://www.linkedin.com/oauth/v2/accessToken",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "client_id" to props.linkedinClientId,
                    "client_secret" to props.linkedinClientSecret,
                    "redirect_uri" to redirectUri,
                ),
            )
        val tokens =
            Tokens(
                t.path("access_token").asText(),
                t.path("refresh_token").asText("").ifBlank { null },
                Instant.now().plusSeconds(t.path("expires_in").asLong(SIXTY_DAYS_S)),
            )
        val me = http.get("https://api.linkedin.com/v2/userinfo", bearer(tokens.accessToken))
        val person =
            ConnectedAccount(
                platform = SocialPlatform.LINKEDIN,
                externalId = "urn:li:person:${me.path("sub").asText()}",
                name = me.path("name").asText("LinkedIn"),
                handle = "LinkedIn profile",
                avatarUrl = me.path("picture").asText("").ifBlank { null },
                tokens = tokens,
                meta = mapOf("author" to "urn:li:person:${me.path("sub").asText()}"),
            )
        if (!props.linkedinScopes.contains("organization")) return listOf(person)
        val orgs =
            runCatching {
                http
                    .get(
                        "https://api.linkedin.com/rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED",
                        rest(tokens.accessToken),
                    ).path("elements")
                    .map { it.path("organization").asText() }
                    .filter { it.startsWith("urn:li:organization:") }
            }.getOrDefault(emptyList())
        return listOf(person) +
            orgs.map { urn ->
                val org = runCatching { http.get("https://api.linkedin.com/rest/organizations/${urn.substringAfterLast(':')}", rest(tokens.accessToken)) }.getOrNull()
                val vanity = org?.path("vanityName")?.asText("").orEmpty()
                ConnectedAccount(
                    platform = SocialPlatform.LINKEDIN,
                    externalId = urn,
                    name = org?.path("localizedName")?.asText("")?.ifBlank { null } ?: urn,
                    handle = vanity.takeIf { it.isNotBlank() }?.let { "linkedin.com/company/$it" },
                    avatarUrl = null,
                    tokens = tokens,
                    meta = mapOf("author" to urn),
                )
            }
    }

    override fun publish(job: PublishJob): PublishResult {
        val author = job.meta["author"] ?: job.externalId
        val token = job.tokens.accessToken
        val images = job.media.filter { it.kind == SocialMediaKind.IMAGE }.map { uploadImage(author, token, it) }
        val video = job.media.firstOrNull { it.kind == SocialMediaKind.VIDEO }?.let { uploadVideo(author, token, it) }
        val content: Map<String, Any>? =
            when {
                video != null -> mapOf("media" to mapOf("id" to video))
                images.size == 1 -> mapOf("media" to mapOf("id" to images[0]))
                images.size > 1 -> mapOf("multiImage" to mapOf("images" to images.map { mapOf("id" to it) }))
                !job.link.isNullOrBlank() -> mapOf("article" to mapOf("source" to job.link, "title" to (job.title ?: job.link)))
                else -> null
            }
        val body =
            buildMap<String, Any> {
                put("author", author)
                put("commentary", escapeCommentary(job.text))
                put("visibility", "PUBLIC")
                put("distribution", mapOf("feedDistribution" to "MAIN_FEED", "targetEntities" to emptyList<Any>(), "thirdPartyDistributionChannels" to emptyList<Any>()))
                put("lifecycleState", "PUBLISHED")
                put("isReshareDisabledByAuthor", false)
                content?.let { put("content", it) }
            }
        val urn =
            pointOfNoReturn(LINKEDIN) {
                val res = http.postJson("https://api.linkedin.com/rest/posts", body, rest(token))
                (res.header("x-restli-id") ?: res.header("x-linkedin-id")).orEmpty().sentOr(LINKEDIN)
            }
        return PublishResult(urn, "https://www.linkedin.com/feed/update/${URLEncoder.encode(urn, Charsets.UTF_8)}")
    }

    private fun uploadImage(
        author: String,
        token: String,
        media: PublishMedia,
    ): String {
        val v =
            http
                .postJson(
                    "https://api.linkedin.com/rest/images?action=initializeUpload",
                    mapOf("initializeUploadRequest" to mapOf("owner" to author)),
                    rest(token),
                ).body
                .path("value")
        http.putFile(v.path("uploadUrl").asText(), media.file, bearer(token) + mapOf("Content-Type" to media.mimeType))
        return v.path("image").asText()
    }

    private fun uploadVideo(
        author: String,
        token: String,
        media: PublishMedia,
    ): String {
        val v =
            http
                .postJson(
                    "https://api.linkedin.com/rest/videos?action=initializeUpload",
                    mapOf(
                        "initializeUploadRequest" to
                            mapOf("owner" to author, "fileSizeBytes" to Files.size(media.file), "uploadCaptions" to false, "uploadThumbnail" to false),
                    ),
                    rest(token),
                ).body
                .path("value")
        val etags = mutableListOf<String>()
        RandomAccessFile(media.file.toFile(), "r").use { file ->
            v.path("uploadInstructions").forEach { part ->
                val first = part.path("firstByte").asLong()
                val last = part.path("lastByte").asLong()
                val bytes = ByteArray((last - first + 1).toInt())
                file.seek(first)
                file.readFully(bytes)
                val res = http.put(part.path("uploadUrl").asText(), HttpRequest.BodyPublishers.ofByteArray(bytes), mapOf("Content-Type" to "application/octet-stream"))
                res.header("etag")?.let { etags += it }
            }
        }
        http.postJson(
            "https://api.linkedin.com/rest/videos?action=finalizeUpload",
            mapOf(
                "finalizeUploadRequest" to
                    mapOf("video" to v.path("video").asText(), "uploadToken" to v.path("uploadToken").asText(""), "uploadedPartIds" to etags),
            ),
            rest(token),
        )
        return v.path("video").asText()
    }

    private companion object {
        const val SIXTY_DAYS_S = 60L * 86_400
        const val LINKEDIN = "LinkedIn"

        /** LinkedIn "little text format": these characters must be escaped in commentary or the post is cut there. */
        private val RESERVED = Regex("""([\\|{}@\[\]()<>#*_~])""")

        fun escapeCommentary(text: String): String = text.replace(RESERVED) { "\\" + it.value }
    }
}
