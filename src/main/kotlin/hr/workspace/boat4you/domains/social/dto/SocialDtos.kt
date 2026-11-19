package hr.workspace.boat4you.domains.social.dto

import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialAiRunStatus
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostAction
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.jpa.SocialPostStatus
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import java.time.Instant
import java.time.LocalDate

/** The admin's `src/types/social.type.ts`, field for field. No token ever leaves the backend. */
data class SocialAccountDto(
    val id: Long,
    val platform: SocialPlatform,
    val site: String?,
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val status: SocialAccountStatus,
    val tokenExpiresAt: Instant?,
    val lastError: String?,
    val connectedAt: Instant,
    val privateOnly: Boolean,
)

data class SocialMediaDto(
    val id: Long,
    val kind: SocialMediaKind,
    val url: String,
    val thumbnailUrl: String?,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationSec: Int?,
)

data class SocialPostTargetDto(
    val id: Long,
    val accountId: Long,
    val platform: SocialPlatform,
    val accountName: String,
    val site: String?,
    val text: String?,
    val title: String?,
    val status: SocialTargetStatus,
    val publishedAt: Instant?,
    val externalUrl: String?,
    val error: String?,
    val attempts: Int,
)

data class SocialPostDto(
    val id: Long,
    val text: String,
    val link: String?,
    val media: List<SocialMediaDto>,
    val status: SocialPostStatus,
    val site: String?,
    val scheduledAt: Instant?,
    val publishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: String?,
    val source: SocialPostSource,
    val note: String?,
    val targets: List<SocialPostTargetDto>,
    /** A Higgsfield video is still being made for this post (AI posts): it cannot be scheduled until it is there. */
    val generating: Boolean = false,
)

data class SocialTargetRequest(
    val accountId: Long,
    val text: String? = null,
    val title: String? = null,
)

data class SocialPostRequest(
    val text: String = "",
    val link: String? = null,
    val mediaIds: List<Long> = emptyList(),
    val targets: List<SocialTargetRequest> = emptyList(),
    val action: SocialPostAction = SocialPostAction.DRAFT,
    val scheduledAt: Instant? = null,
    val note: String? = null,
)

data class ConnectRequest(
    val returnUrl: String,
    val site: String? = null,
)

data class CredentialsRequest(
    val platform: SocialPlatform,
    val identifier: String,
    val secret: String,
    val site: String? = null,
) {
    override fun toString() = "CredentialsRequest($platform $identifier)"
}

data class AccountPatch(
    /** Site id, or "" / null for "all sites" (shared channel such as our YouTube). */
    val site: String? = null,
)

data class SocialApiKeyDto(
    val id: Long,
    val name: String,
    val prefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val draftsOnly: Boolean,
    /** Only in the create answer. */
    val secret: String? = null,
)

data class ApiKeyRequest(
    val name: String,
    val draftsOnly: Boolean = true,
)

data class SocialAiSettingDto(
    val site: String,
    val siteName: String,
    val enabled: Boolean,
    val publishTime: String,
    val language: String,
    val accountIds: List<Long>,
    val requireApproval: Boolean,
    val useVideo: Boolean,
    val guidance: String?,
    val updatedAt: Instant?,
)

data class SocialAiRunDto(
    val id: Long,
    val site: String,
    val runDate: LocalDate,
    val status: SocialAiRunStatus,
    val topic: String?,
    val postId: Long?,
    val error: String?,
    val createdAt: Instant,
)

data class SocialAiOverviewDto(
    /** Anthropic key present (the site chat's ANTHROPIC_API_KEY). */
    val aiConfigured: Boolean,
    val higgsfieldConfigured: Boolean,
    /** Why this server cannot make its own Reel from photos (no ffmpeg), or null when it can. */
    val reelUnavailable: String? = null,
    val settings: List<SocialAiSettingDto>,
    val runs: List<SocialAiRunDto>,
)

/** Automation API (`/api/social/v1/posts`): ids or URLs for media, ids or platforms (+ site) for channels. */
data class AutomationPostRequest(
    val text: String = "",
    val link: String? = null,
    val mediaIds: List<Long> = emptyList(),
    val mediaUrls: List<String> = emptyList(),
    val accountIds: List<Long> = emptyList(),
    val platforms: List<SocialPlatform> = emptyList(),
    val site: String? = null,
    val perPlatform: Map<SocialPlatform, PerPlatform> = emptyMap(),
    val scheduledAt: Instant? = null,
    val note: String? = null,
) {
    data class PerPlatform(
        val text: String? = null,
        val title: String? = null,
    )
}

/** Photos (media ids, in order) to turn into a Reel. */
data class ReelRequest(
    val mediaIds: List<Long> = emptyList(),
)

data class MediaUrlRequest(
    val url: String,
)
