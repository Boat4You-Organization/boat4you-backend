package hr.workspace.boat4you.domains.social.connectors

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component

/**
 * Facebook pages and their Instagram business accounts through one Meta app (Graph API, Facebook Login for Business).
 * One login returns every page the admin manages (we have one per site) and the Instagram account linked to each page;
 * each becomes its own channel. Page tokens made from a long-lived user token do not expire; Instagram calls use the
 * page's token.
 *
 * Facebook: text/link → `/{page}/feed`; one photo → `/{page}/photos`; several → unpublished photos + `attached_media`;
 * video → `/{page}/videos` with `file_url`. Instagram: container (`image_url` / Reels `video_url` / carousel of
 * children) → wait for `status_code = FINISHED` → `media_publish` → `permalink`.
 */
@Component
class MetaConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    override val platforms = setOf(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM)

    private val graph get() = "https://graph.facebook.com/${props.metaGraphVersion}"

    override fun notConfiguredReason(): String? =
        if (props.metaAppId.isBlank() || props.metaAppSecret.isBlank()) "Meta app not configured (SOCIAL_META_APP_ID / SOCIAL_META_APP_SECRET)" else null

    private val redirectUri get() = props.callbackUrl("meta")

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://www.facebook.com/${props.metaGraphVersion}/dialog/oauth",
            mapOf(
                "client_id" to props.metaAppId,
                "redirect_uri" to redirectUri,
                "state" to state,
                "response_type" to "code",
                "scope" to SCOPES,
            ),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val short =
            http
                .get(
                    query(
                        "$graph/oauth/access_token",
                        mapOf("client_id" to props.metaAppId, "client_secret" to props.metaAppSecret, "redirect_uri" to redirectUri, "code" to code),
                    ),
                ).path("access_token")
                .asText()
        val long =
            http
                .get(
                    query(
                        "$graph/oauth/access_token",
                        mapOf(
                            "grant_type" to "fb_exchange_token",
                            "client_id" to props.metaAppId,
                            "client_secret" to props.metaAppSecret,
                            "fb_exchange_token" to short,
                        ),
                    ),
                ).path("access_token")
                .asText()
        val pages =
            http.get(
                query(
                    "$graph/me/accounts",
                    mapOf(
                        "fields" to "id,name,access_token,link,picture{url},instagram_business_account{id,username,name,profile_picture_url}",
                        "limit" to "100",
                        "access_token" to long,
                    ),
                ),
            )
        return pages.path("data").flatMap { page -> accountsOf(page) }
    }

    private fun accountsOf(page: JsonNode): List<ConnectedAccount> {
        val pageId = page.path("id").asText()
        val token = Tokens(page.path("access_token").asText(), null, null)
        val fb =
            ConnectedAccount(
                platform = SocialPlatform.FACEBOOK,
                externalId = pageId,
                name = page.path("name").asText(pageId),
                handle = page.path("link").asText("").ifBlank { "facebook.com/$pageId" },
                avatarUrl = page.path("picture").path("data").path("url").asText("").ifBlank { null },
                tokens = token,
                meta = mapOf("pageId" to pageId),
            )
        val ig = page.path("instagram_business_account")
        if (ig.isMissingNode || ig.path("id").asText().isBlank()) return listOf(fb)
        val username = ig.path("username").asText("")
        return listOf(
            fb,
            ConnectedAccount(
                platform = SocialPlatform.INSTAGRAM,
                externalId = ig.path("id").asText(),
                name = username.ifBlank { ig.path("name").asText("Instagram") },
                handle = username.takeIf { it.isNotBlank() }?.let { "@$it" },
                avatarUrl = ig.path("profile_picture_url").asText("").ifBlank { null },
                tokens = token,
                meta = mapOf("pageId" to pageId, "igUserId" to ig.path("id").asText()),
            ),
        )
    }

    override fun publish(job: PublishJob): PublishResult =
        when (job.platform) {
            SocialPlatform.FACEBOOK -> publishFacebook(job)
            SocialPlatform.INSTAGRAM -> publishInstagram(job)
            else -> throw PublishException("Meta connector cannot publish to ${job.platform}")
        }

    private fun publishFacebook(job: PublishJob): PublishResult {
        val page = job.externalId
        val token = job.tokens.accessToken
        val video = job.media.firstOrNull { it.kind == SocialMediaKind.VIDEO }
        val photos = job.media.filter { it.kind == SocialMediaKind.IMAGE }
        return when {
            video != null -> {
                val id =
                    http
                        .postForm("$graph/$page/videos", mapOf("file_url" to video.url, "description" to job.text, "access_token" to token))
                        .path("id")
                        .asText()
                PublishResult(id, "https://www.facebook.com/$page/videos/$id")
            }
            photos.size == 1 -> {
                val res = http.postForm("$graph/$page/photos", mapOf("url" to photos[0].url, "message" to withLink(job), "access_token" to token))
                val postId = res.path("post_id").asText("").ifBlank { res.path("id").asText() }
                PublishResult(postId, "https://www.facebook.com/$postId")
            }
            photos.size > 1 -> {
                val ids =
                    photos.map {
                        http.postForm("$graph/$page/photos", mapOf("url" to it.url, "published" to "false", "access_token" to token)).path("id").asText()
                    }
                val form =
                    mutableMapOf<String, String?>("message" to withLink(job), "access_token" to token).apply {
                        ids.forEachIndexed { i, id -> put("attached_media[$i]", """{"media_fbid":"$id"}""") }
                    }
                val id = http.postForm("$graph/$page/feed", form).path("id").asText()
                PublishResult(id, "https://www.facebook.com/$id")
            }
            else -> {
                val id = http.postForm("$graph/$page/feed", mapOf("message" to job.text, "link" to job.link, "access_token" to token)).path("id").asText()
                PublishResult(id, "https://www.facebook.com/$id")
            }
        }
    }

    /** A photo post has no link preview: the link goes at the end of the text unless it is already there. */
    private fun withLink(job: PublishJob): String = if (job.link.isNullOrBlank() || job.text.contains(job.link)) job.text else "${job.text}\n\n${job.link}".trim()

    private fun publishInstagram(job: PublishJob): PublishResult {
        val ig = job.meta["igUserId"] ?: job.externalId
        val token = job.tokens.accessToken
        if (job.media.isEmpty()) throw PublishException("Instagram needs a photo or video")
        val container =
            if (job.media.size == 1) {
                createIgItem(ig, token, job.media[0], caption = job.text, carouselItem = false)
            } else {
                val children = job.media.map { createIgItem(ig, token, it, caption = null, carouselItem = true) }
                children.forEach { waitIg(it, token) }
                http
                    .postForm(
                        "$graph/$ig/media",
                        mapOf("media_type" to "CAROUSEL", "children" to children.joinToString(","), "caption" to job.text, "access_token" to token),
                    ).path("id")
                    .asText()
            }
        waitIg(container, token)
        val mediaId = http.postForm("$graph/$ig/media_publish", mapOf("creation_id" to container, "access_token" to token)).path("id").asText()
        val permalink = runCatching { http.get(query("$graph/$mediaId", mapOf("fields" to "permalink", "access_token" to token))).path("permalink").asText("") }
        return PublishResult(mediaId, permalink.getOrNull()?.ifBlank { null })
    }

    private fun createIgItem(
        ig: String,
        token: String,
        media: PublishMedia,
        caption: String?,
        carouselItem: Boolean,
    ): String {
        val form = mutableMapOf<String, String?>("access_token" to token)
        if (media.kind == SocialMediaKind.VIDEO) {
            form["media_type"] = if (carouselItem) "VIDEO" else "REELS"
            form["video_url"] = media.url
            if (!carouselItem) form["share_to_feed"] = "true"
        } else {
            form["image_url"] = media.url
        }
        if (carouselItem) form["is_carousel_item"] = "true"
        if (caption != null) form["caption"] = caption
        return http.postForm("$graph/$ig/media", form).path("id").asText()
    }

    /** Containers process asynchronously (videos for a minute or more): poll until FINISHED, up to ~10 minutes. */
    private fun waitIg(
        container: String,
        token: String,
    ) {
        repeat(POLLS) {
            val status = http.get(query("$graph/$container", mapOf("fields" to "status_code,status", "access_token" to token)))
            when (status.path("status_code").asText()) {
                "FINISHED", "PUBLISHED" -> return
                "ERROR", "EXPIRED" -> throw PublishException("Instagram could not process the media: ${status.path("status").asText("error")}")
                else -> sleeper(POLL_MS)
            }
        }
        throw PublishException("Instagram is still processing the media", retryable = true)
    }

    private companion object {
        const val SCOPES =
            "pages_show_list,pages_read_engagement,pages_manage_posts,instagram_basic,instagram_content_publish,business_management"
        const val POLLS = 120
        const val POLL_MS = 5000L
    }
}
