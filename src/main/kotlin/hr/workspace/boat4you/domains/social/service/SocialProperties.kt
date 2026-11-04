package hr.workspace.boat4you.domains.social.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Objave configuration (`social.*` in application.yml, every secret from the env). A network whose app id / secret is
 * blank is "not configured": the admin shows it, connecting it answers 409 with that reason, nothing else breaks.
 * The developer apps are ours (Mario creates them, see the design doc § "What Mario has to do"); redirect URI for every
 * OAuth network: `{publicApiUrl}/public/social/oauth/{platform}/callback`.
 */
@Component
@Suppress("LongParameterList") // one @Value per setting, like ElevenLabsProperties; every secret comes from the env
class SocialProperties(
    /** Base64 of 32 random bytes; AES-256-GCM key for network tokens. Blank = connecting any network is refused. */
    @Value("\${social.token-key:}") val tokenKey: String,
    /** This API as the networks and browsers reach it (https://api.boat4you.com): OAuth callbacks and media URLs. */
    @Value("\${social.public-api-url:\${server.host:https://localhost:8443}}") val publicApiUrl: String,
    /** The admin origin(s) an OAuth round trip may return to, comma separated. */
    @Value("\${social.admin-origins:\${server.host-admin-public:http://localhost:5173}}") val adminOrigins: String,
    /** Where uploaded and generated files live. */
    @Value("\${social.media-dir:\${application.upload.directory:/tmp/boat4you_data}/social}") val mediaDir: String,
    @Value("\${social.max-image-mb:20}") val maxImageMb: Long,
    @Value("\${social.max-video-mb:500}") val maxVideoMb: Long,
    // Meta: one app for Facebook pages and Instagram (Facebook Login for Business); Threads has its own app id.
    @Value("\${social.meta.app-id:}") val metaAppId: String,
    @Value("\${social.meta.app-secret:}") val metaAppSecret: String,
    @Value("\${social.meta.graph-version:v23.0}") val metaGraphVersion: String,
    @Value("\${social.threads.app-id:}") val threadsAppId: String,
    @Value("\${social.threads.app-secret:}") val threadsAppSecret: String,
    @Value("\${social.tiktok.client-key:}") val tiktokClientKey: String,
    @Value("\${social.tiktok.client-secret:}") val tiktokClientSecret: String,
    /** Until TikTok's audit passes, posts can only be private (SELF_ONLY). */
    @Value("\${social.tiktok.audited:false}") val tiktokAudited: Boolean,
    @Value("\${social.google.client-id:}") val googleClientId: String,
    @Value("\${social.google.client-secret:}") val googleClientSecret: String,
    @Value("\${social.linkedin.client-id:}") val linkedinClientId: String,
    @Value("\${social.linkedin.client-secret:}") val linkedinClientSecret: String,
    /** Org posting needs LinkedIn's Community Management API approval; without it only the personal profile works. */
    @Value("\${social.linkedin.scopes:openid profile w_member_social}") val linkedinScopes: String,
    @Value("\${social.linkedin.version:202510}") val linkedinVersion: String,
    @Value("\${social.bluesky.service:https://bsky.social}") val blueskyService: String,
    // AI daily posts: the Anthropic key the site chat already uses; the model is ours to choose.
    @Value("\${chat.anthropic-api-key:}") val anthropicApiKey: String,
    @Value("\${chat.anthropic-base-url:https://api.anthropic.com}") val anthropicBaseUrl: String,
    @Value("\${social.ai.model:claude-opus-5-5}") val aiModel: String,
    @Value("\${social.ai.self-base-url:\${chat.self-base-url:http://127.0.0.1:8080}}") val selfBaseUrl: String,
    // Higgsfield (image → short video). Key id + secret from the Higgsfield dashboard.
    @Value("\${social.higgsfield.api-key-id:}") val higgsfieldKeyId: String,
    @Value("\${social.higgsfield.api-key-secret:}") val higgsfieldKeySecret: String,
    @Value("\${social.higgsfield.base-url:https://api.higgsfield.ai}") val higgsfieldBaseUrl: String,
    /** The image-to-video model endpoint (Higgsfield publishes one path per model). */
    @Value("\${social.higgsfield.image-to-video-path:/wan/v2.7/image-to-video}") val higgsfieldImageToVideoPath: String,
    /** Status of a request; `{id}` is replaced by the request id the submit answered. */
    @Value("\${social.higgsfield.status-path:/requests/{id}/status}") val higgsfieldStatusPath: String,
) {
    val adminOriginList: List<String> get() = adminOrigins.split(",").map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    fun callbackUrl(platformPath: String): String = "${publicApiUrl.trimEnd('/')}/public/social/oauth/$platformPath/callback"

    val aiConfigured: Boolean get() = anthropicApiKey.isNotBlank()

    val higgsfieldConfigured: Boolean get() = higgsfieldKeyId.isNotBlank() && higgsfieldKeySecret.isNotBlank()
}
