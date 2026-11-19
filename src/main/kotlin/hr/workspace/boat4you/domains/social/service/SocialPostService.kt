package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.social.dto.SocialMediaDto
import hr.workspace.boat4you.domains.social.dto.SocialPostDto
import hr.workspace.boat4you.domains.social.dto.SocialPostRequest
import hr.workspace.boat4you.domains.social.dto.SocialPostTargetDto
import hr.workspace.boat4you.domains.social.dto.SocialTargetRequest
import hr.workspace.boat4you.domains.social.jpa.SocialAccount
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialGenerationRepository
import hr.workspace.boat4you.domains.social.jpa.SocialGenerationStatus
import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPost
import hr.workspace.boat4you.domains.social.jpa.SocialPostAction
import hr.workspace.boat4you.domains.social.jpa.SocialPostRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.jpa.SocialPostStatus
import hr.workspace.boat4you.domains.social.jpa.SocialPostTarget
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import jakarta.persistence.criteria.JoinType
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant

/** Who makes or changes a post: a person in the admin, the automation key, or the daily AI. */
data class SocialActor(
    val userId: Long?,
    val name: String?,
    val source: SocialPostSource,
    val apiKeyId: Long? = null,
    /** An automation key that may only create drafts. */
    val draftsOnly: Boolean = false,
)

data class SocialPostFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val status: SocialPostStatus? = null,
    val platform: SocialPlatform? = null,
    val site: String? = null,
    val page: Int = 0,
    val size: Int = 200,
)

/**
 * Posts and their channels. A post is DRAFT (targets PENDING), SCHEDULED (targets SCHEDULED, due at the schedule) or,
 * for "publish now", PUBLISHING (targets SCHEDULED, due now: [SocialPublisher] takes them within seconds). Only DRAFT and
 * SCHEDULED posts with no channel already taken by the publisher can change. The network rules ([SocialRules]) are
 * checked before anything can be scheduled.
 */
@Service
class SocialPostService(
    private val posts: SocialPostRepository,
    private val accounts: SocialAccountRepository,
    private val mediaRepo: SocialMediaRepository,
    private val generations: SocialGenerationRepository,
    private val store: SocialMediaStore,
) {
    @Transactional(readOnly = true)
    fun list(filter: SocialPostFilter): Page<SocialPostDto> {
        val spec =
            Specification<SocialPost> { root, query, cb ->
                val preds = mutableListOf<jakarta.persistence.criteria.Predicate>()
                val whenExpr = cb.coalesce<Instant>().value(root.get("scheduledAt")).value(root.get("publishedAt")).value(root.get("createdAt"))
                filter.from?.let { preds += cb.greaterThanOrEqualTo(whenExpr, it) }
                filter.to?.let { preds += cb.lessThan(whenExpr, it) }
                filter.status?.let { preds += cb.equal(root.get<SocialPostStatus>("status"), it) }
                if (filter.platform != null || filter.site != null) {
                    val t = root.join<SocialPost, SocialPostTarget>("targets", JoinType.INNER)
                    val a = t.join<SocialPostTarget, Any>("account", JoinType.INNER)
                    filter.platform?.let { preds += cb.equal(a.get<SocialPlatform>("platform"), it) }
                    filter.site?.let { site -> preds += cb.or(cb.equal(root.get<String>("site"), site), cb.equal(a.get<String>("site"), site)) }
                    query?.distinct(true)
                }
                cb.and(*preds.toTypedArray())
            }
        val page = PageRequest.of(filter.page.coerceAtLeast(0), filter.size.coerceIn(1, MAX_PAGE), Sort.by(Sort.Direction.DESC, "id"))
        val generating = generations.findAllByStatusIn(OPEN_GENERATION).mapNotNull { it.postId }.toSet()
        return posts.findAll(spec, page).map { toDto(it, generating) }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): SocialPostDto = toDto(load(id))

    @Transactional
    fun create(
        req: SocialPostRequest,
        actor: SocialActor,
        site: String? = null,
    ): SocialPostDto {
        val post =
            SocialPost().apply {
                source = actor.source
                createdBy = actor.userId
                createdByName = actor.name
                apiKeyId = actor.apiKeyId
            }
        apply(post, req, actor, site)
        return toDto(posts.save(post))
    }

    @Transactional
    fun update(
        id: Long,
        req: SocialPostRequest,
        actor: SocialActor,
    ): SocialPostDto {
        val post = load(id)
        requireEditable(post)
        apply(post, req, actor, post.site)
        return toDto(posts.save(post))
    }

    @Transactional
    fun cancel(id: Long): SocialPostDto {
        val post = load(id)
        if (post.status != SocialPostStatus.SCHEDULED && post.status != SocialPostStatus.PUBLISHING) {
            throw SocialConflictException("Only a scheduled post can be cancelled")
        }
        post.targets.filter { it.status == SocialTargetStatus.SCHEDULED }.forEach {
            it.status = SocialTargetStatus.CANCELLED
            it.dueAt = null
        }
        post.status = deriveStatus(post.targets.map { it.status }, post.status)
        return toDto(posts.save(post))
    }

    @Transactional
    fun retry(id: Long): SocialPostDto {
        val post = load(id)
        val failed = post.targets.filter { it.status == SocialTargetStatus.FAILED }
        if (failed.isEmpty()) throw SocialConflictException("Nothing failed")
        failed.forEach {
            if (it.account?.status != SocialAccountStatus.CONNECTED || it.account?.disconnectedAt != null) {
                throw SocialValidationException("${it.account?.name}: connect the channel again first")
            }
            it.status = SocialTargetStatus.SCHEDULED
            it.dueAt = Instant.now()
            it.error = null
            // A person decided to send again: forget what the network accepted last time (TikTok publish_id).
            it.externalId = null
        }
        post.status = SocialPostStatus.PUBLISHING
        return toDto(posts.save(post))
    }

    @Transactional
    fun delete(id: Long) {
        val post = load(id)
        if (post.status !in DELETABLE) throw SocialConflictException("Only drafts, cancelled and failed posts can be deleted")
        posts.delete(post)
    }

    /** A post the publisher has not touched yet: DRAFT, or SCHEDULED with every channel still waiting. */
    private fun requireEditable(post: SocialPost) {
        val waiting = post.targets.all { it.status == SocialTargetStatus.PENDING || it.status == SocialTargetStatus.SCHEDULED }
        if (post.status !in EDITABLE || !waiting) throw SocialConflictException("This post already went out; duplicate it instead")
    }

    private fun apply(
        post: SocialPost,
        req: SocialPostRequest,
        actor: SocialActor,
        explicitSite: String?,
    ) {
        val action = if (actor.draftsOnly) SocialPostAction.DRAFT else req.action
        val text = req.text.trim()
        if (text.length > MAX_TEXT) throw SocialValidationException("Text too long")
        val link = req.link?.trim()?.ifBlank { null }
        if (link != null && !isHttpUrl(link)) throw SocialValidationException("The link must be a full http(s) address")
        val media = req.mediaIds.distinct().map { id -> mediaRepo.findById(id).orElseThrow { SocialValidationException("Unknown media $id") } }
        val wanted = req.targets.distinctBy { it.accountId }
        val accountById = accounts.findAllById(wanted.map { it.accountId }).associateBy { it.id }
        wanted.forEach {
            val account = accountById[it.accountId]
            if (account == null || account.disconnectedAt != null) throw SocialValidationException("Unknown channel ${it.accountId}")
        }
        if (action != SocialPostAction.DRAFT) checkPublishable(post, wanted, accountById, text, media)
        val at = scheduleOf(action, req)

        post.text = text
        post.link = link
        post.note = req.note?.trim()?.ifBlank { null }?.take(MAX_NOTE)
        post.media.clear()
        post.media.addAll(media)
        post.scheduledAt = at
        post.status =
            when (action) {
                SocialPostAction.DRAFT -> SocialPostStatus.DRAFT
                SocialPostAction.SCHEDULE -> SocialPostStatus.SCHEDULED
                SocialPostAction.PUBLISH_NOW -> SocialPostStatus.PUBLISHING
            }
        syncTargets(post, wanted, accountById, action, at)
        val sites = post.targets.mapNotNull { it.account?.site }.toSet()
        post.site = explicitSite?.takeIf { SiteIds.isKnown(it) } ?: sites.singleOrNull()
    }

    /** Before a post may be scheduled or published: a channel, every channel connected and within its network's rules. */
    private fun checkPublishable(
        post: SocialPost,
        wanted: List<SocialTargetRequest>,
        accountById: Map<Long?, SocialAccount>,
        text: String,
        media: List<SocialMedia>,
    ) {
        if (wanted.isEmpty()) throw SocialValidationException("Pick at least one channel")
        val mediaInfo = media.map { SocialRules.MediaInfo(it.kind, it.width, it.height) }
        val problems =
            wanted.flatMap { t ->
                val account = accountById.getValue(t.accountId)
                val errs = mutableListOf<String>()
                if (account.status != SocialAccountStatus.CONNECTED) errs += "connect it again"
                errs += SocialRules.errors(account.platform!!, t.text?.trim()?.ifBlank { null } ?: text, t.title, mediaInfo)
                errs.map { "${account.platform} ${account.name}: $it" }
            }
        if (problems.isNotEmpty()) throw SocialValidationException(problems.joinToString("; "))
        if (post.id != null && generations.findAllByPostId(post.id!!).any { it.status in OPEN_GENERATION }) {
            throw SocialConflictException("The video for this post is still being made")
        }
    }

    private fun scheduleOf(
        action: SocialPostAction,
        req: SocialPostRequest,
    ): Instant? {
        if (action != SocialPostAction.SCHEDULE) return null
        val s = req.scheduledAt ?: throw SocialValidationException("Pick the date and time")
        if (s.isBefore(Instant.now().plusSeconds(MIN_LEAD_S))) throw SocialValidationException("Pick a time at least 1 minute from now")
        return s
    }

    /** Channels in place (the (post, account) pair is unique; delete-then-insert would collide on flush). */
    private fun syncTargets(
        post: SocialPost,
        wanted: List<SocialTargetRequest>,
        accountById: Map<Long?, SocialAccount>,
        action: SocialPostAction,
        at: Instant?,
    ) {
        val draft = action == SocialPostAction.DRAFT
        val due = if (draft) null else at ?: Instant.now()
        // Never rewrite a channel the publisher has taken (also guarded by the target's version against a claim meanwhile).
        if (post.targets.any { it.status in TAKEN }) throw SocialConflictException("This post already went out; duplicate it instead")
        val keep = wanted.map { it.accountId }.toSet()
        post.targets.removeIf { it.account?.id !in keep }
        wanted.forEach { w ->
            val target =
                post.targets.firstOrNull { it.account?.id == w.accountId }
                    ?: SocialPostTarget().also {
                        it.post = post
                        it.account = accountById.getValue(w.accountId)
                        post.targets.add(it)
                    }
            target.text = w.text?.trim()?.ifBlank { null }?.takeIf { it != post.text }
            target.title = w.title?.trim()?.ifBlank { null }?.take(MAX_TITLE)
            target.status = if (draft) SocialTargetStatus.PENDING else SocialTargetStatus.SCHEDULED
            target.dueAt = due
            target.error = null
        }
    }

    private fun load(id: Long): SocialPost = posts.findById(id).orElseThrow { NoSuchElementException("Post $id") }

    fun toDto(
        p: SocialPost,
        generatingIds: Set<Long>? = null,
    ): SocialPostDto =
        SocialPostDto(
            id = p.id!!,
            text = p.text,
            link = p.link,
            media = p.media.map { mediaDto(it) },
            status = p.status,
            site = p.site,
            scheduledAt = p.scheduledAt,
            publishedAt = p.publishedAt,
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
            createdBy = p.createdByName,
            source = p.source,
            note = p.note,
            targets =
                p.targets.map { t ->
                    val a = t.account!!
                    SocialPostTargetDto(
                        id = t.id!!,
                        accountId = a.id!!,
                        platform = a.platform!!,
                        accountName = a.name,
                        site = a.site,
                        text = t.text,
                        title = t.title,
                        status = t.status,
                        publishedAt = t.publishedAt,
                        externalUrl = t.externalUrl,
                        error = t.error,
                        attempts = t.attempts,
                    )
                },
            generating =
                generatingIds?.contains(p.id) ?: generations.findAllByPostId(p.id!!).any { it.status in OPEN_GENERATION },
        )

    /** The stored media of these ids, in the order given. */
    fun mediaInOrder(ids: List<Long>): List<SocialMedia> =
        ids.distinct().map { id -> mediaRepo.findById(id).orElseThrow { SocialValidationException("Unknown media $id") } }

    fun mediaDto(m: SocialMedia): SocialMediaDto =
        SocialMediaDto(
            id = m.id!!,
            kind = m.kind,
            url = store.publicUrl(m),
            thumbnailUrl = store.thumbnailUrl(m),
            fileName = m.fileName,
            mimeType = m.mimeType,
            sizeBytes = m.sizeBytes,
            width = m.width,
            height = m.height,
            durationSec = m.durationSec,
        )

    companion object {
        private const val MAX_PAGE = 500
        private const val MAX_TEXT = 70_000
        private const val MAX_NOTE = 500
        private const val MAX_TITLE = 200
        private const val MIN_LEAD_S = 60L
        private val EDITABLE = setOf(SocialPostStatus.DRAFT, SocialPostStatus.SCHEDULED)
        private val TAKEN = setOf(SocialTargetStatus.PUBLISHING, SocialTargetStatus.PUBLISHED)
        val OPEN_GENERATION = setOf(SocialGenerationStatus.QUEUED, SocialGenerationStatus.RUNNING)
        private val DELETABLE = setOf(SocialPostStatus.DRAFT, SocialPostStatus.CANCELLED, SocialPostStatus.FAILED)

        fun isHttpUrl(s: String): Boolean =
            runCatching {
                val u = URI(s)
                (u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrBlank() && u.userInfo == null
            }.getOrDefault(false)

        /**
         * The post from its channels: any still going → PUBLISHING (or SCHEDULED when all wait for their time), all
         * published → PUBLISHED, some → PARTIAL, none and something failed → FAILED, all cancelled → CANCELLED.
         */
        fun deriveStatus(
            targets: List<SocialTargetStatus>,
            current: SocialPostStatus,
        ): SocialPostStatus {
            if (targets.isEmpty()) return current
            val published = targets.count { it == SocialTargetStatus.PUBLISHED }
            val failed = targets.count { it == SocialTargetStatus.FAILED }
            return when {
                targets.any { it == SocialTargetStatus.PUBLISHING } -> SocialPostStatus.PUBLISHING
                targets.any { it == SocialTargetStatus.SCHEDULED } ->
                    if (current == SocialPostStatus.SCHEDULED && published == 0 && failed == 0) SocialPostStatus.SCHEDULED else SocialPostStatus.PUBLISHING
                targets.all { it == SocialTargetStatus.PENDING } -> SocialPostStatus.DRAFT
                published == targets.size -> SocialPostStatus.PUBLISHED
                published > 0 -> SocialPostStatus.PARTIAL
                failed > 0 -> SocialPostStatus.FAILED
                else -> SocialPostStatus.CANCELLED
            }
        }
    }
}
