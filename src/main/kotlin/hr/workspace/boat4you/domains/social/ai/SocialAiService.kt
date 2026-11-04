package hr.workspace.boat4you.domains.social.ai

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.social.dto.SocialAiOverviewDto
import hr.workspace.boat4you.domains.social.dto.SocialAiRunDto
import hr.workspace.boat4you.domains.social.dto.SocialAiSettingDto
import hr.workspace.boat4you.domains.social.dto.SocialPostDto
import hr.workspace.boat4you.domains.social.dto.SocialPostRequest
import hr.workspace.boat4you.domains.social.dto.SocialTargetRequest
import hr.workspace.boat4you.domains.social.jpa.SocialAccount
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialAiRun
import hr.workspace.boat4you.domains.social.jpa.SocialAiRunRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAiRunStatus
import hr.workspace.boat4you.domains.social.jpa.SocialAiSetting
import hr.workspace.boat4you.domains.social.jpa.SocialAiSettingRepository
import hr.workspace.boat4you.domains.social.jpa.SocialGeneration
import hr.workspace.boat4you.domains.social.jpa.SocialGenerationRepository
import hr.workspace.boat4you.domains.social.jpa.SocialGenerationStatus
import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialMediaOrigin
import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostAction
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.service.SocialActor
import hr.workspace.boat4you.domains.social.service.SocialConflictException
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import hr.workspace.boat4you.domains.social.service.SocialPostService
import hr.workspace.boat4you.domains.social.service.SocialProperties
import hr.workspace.boat4you.domains.social.service.SocialRules
import hr.workspace.boat4you.domains.social.service.SocialValidationException
import jakarta.annotation.PreDestroy
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The daily AI post per site ("AI dnevne objave"). For every site with AI on, a few hours before its publish time:
 * pick a boat of the site's fleet that was not featured lately, take our photos of it, let Claude write a text per
 * network, and create the post for the site's chosen channels — as a DRAFT waiting for our approval, or SCHEDULED
 * at the publish time when the site's setting says the AI may publish alone. With "video" on, Higgsfield makes a short
 * video from the best photo; when it is ready a second post with that video goes to TikTok and YouTube (the photo
 * post serves the other networks). One run per site and day (unique row), whatever the number of nodes.
 */
@Service
class SocialAiService(
    private val settings: SocialAiSettingRepository,
    private val runs: SocialAiRunRepository,
    private val generations: SocialGenerationRepository,
    private val accounts: SocialAccountRepository,
    private val mediaRepository: SocialMediaRepository,
    private val brands: BrandRegistry,
    private val picker: SocialBoatPicker,
    private val writer: SocialAiWriter,
    private val higgsfield: HiggsfieldClient,
    private val store: SocialMediaStore,
    private val postService: SocialPostService,
    private val props: SocialProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One "Napravi sada" at a time per node, off the request thread. */
    private val background: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "social-ai-run").apply { isDaemon = true } }

    fun overview(): SocialAiOverviewDto {
        val saved = settings.findAll().associateBy { it.site }
        return SocialAiOverviewDto(
            aiConfigured = props.aiConfigured,
            higgsfieldConfigured = props.higgsfieldConfigured,
            settings = SiteIds.ALL.map { site -> settingDto(site, saved[site]) },
            runs = runs.findTop50ByOrderByCreatedAtDesc().map { runDto(it) },
        )
    }

    fun saveSetting(
        site: String,
        dto: SocialAiSettingDto,
        by: Long?,
    ): SocialAiSettingDto {
        if (!SiteIds.isKnown(site)) throw SocialValidationException("Unknown site $site")
        if (!TIME.matches(dto.publishTime)) throw SocialValidationException("Time must be HH:mm")
        if (dto.language.lowercase() !in LANGUAGES) throw SocialValidationException("Unsupported language ${dto.language}")
        val known = accounts.findAllById(dto.accountIds).filter { it.disconnectedAt == null && (it.site == site || it.site == null) }.map { it.id!! }
        val s = settings.findById(site).orElse(SocialAiSetting().apply { this.site = site })
        s.enabled = dto.enabled
        s.publishTime = dto.publishTime
        s.language = dto.language.lowercase()
        s.accountIds = known.toMutableList()
        s.requireApproval = dto.requireApproval
        s.useVideo = dto.useVideo
        s.guidance = dto.guidance?.trim()?.take(GUIDANCE_MAX)?.ifBlank { null }
        s.updatedBy = by
        s.updatedAt = Instant.now()
        return settingDto(site, settings.save(s))
    }

    /** Every 10 minutes: sites whose generation time has come today and have no run yet. */
    @Scheduled(cron = "\${social.ai.cron:0 */10 * * * *}", zone = "Europe/Zagreb")
    @SchedulerLock(name = "social_ai_daily", lockAtMostFor = "PT30M")
    fun daily() {
        if (!props.aiConfigured) return
        val now = ZonedDateTime.now(ZONE)
        settings.findAll().filter { it.enabled }.forEach { s ->
            val publishAt = LocalTime.parse(s.publishTime)
            val generateAt = publishAt.minus(LEAD).coerceAtLeast(EARLIEST)
            if (now.toLocalTime().isBefore(generateAt)) return@forEach
            if (runs.findBySiteAndRunDate(s.site!!, now.toLocalDate()) != null) return@forEach
            runCatching { run(s.site!!, now.toLocalDate(), force = false) }.onFailure { log.error("Social AI run for {} failed", s.site, it) }
        }
    }

    /**
     * "Napravi sada" from the admin: claims today's run (409 when it is made already) and makes it on [background]
     * (Claude and the photo downloads take minutes, longer than the proxy waits); answers the RUNNING run at once, the
     * admin polls the run list until it is done.
     */
    fun start(site: String): SocialAiRunDto {
        val setting = settings.findById(site).orElseThrow { SocialValidationException("Set up the AI for $site first") }
        store.requireShared()
        val date = LocalDate.now(ZONE)
        val run = claimRun(site, date, force = true)
        background.execute { produceOrFail(setting, run, date) }
        return runDto(run)
    }

    /** The daily job's run for one site, made in the caller's thread. */
    fun run(
        site: String,
        date: LocalDate = LocalDate.now(ZONE),
        force: Boolean = true,
    ): SocialAiRunDto {
        val setting = settings.findById(site).orElseThrow { SocialValidationException("Set up the AI for $site first") }
        store.requireShared()
        return produceOrFail(setting, claimRun(site, date, force), date)
    }

    @PreDestroy
    fun stopBackground() {
        background.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught") // any failure becomes the run's error for the admin, never a crashed job
    private fun produceOrFail(
        setting: SocialAiSetting,
        run: SocialAiRun,
        date: LocalDate,
    ): SocialAiRunDto {
        val site = setting.site!!
        try {
            val result = produce(setting, run, date)
            return runDto(result)
        } catch (e: Exception) {
            log.warn("Social AI run {} {} failed: {}", site, date, e.message)
            return runDto(
                updateRun(run.id!!) {
                    it.status = SocialAiRunStatus.FAILED
                    it.error = (e.message ?: e.javaClass.simpleName).take(ERROR_MAX)
                },
            )
        }
    }

    private fun claimRun(
        site: String,
        date: LocalDate,
        force: Boolean,
    ): SocialAiRun =
        tx.execute {
            val existing = runs.findBySiteAndRunDate(site, date)
            when {
                existing == null ->
                    runs.saveAndFlush(
                        SocialAiRun().apply {
                            this.site = site
                            runDate = date
                        },
                    )
                force && (existing.status in setOf(SocialAiRunStatus.FAILED, SocialAiRunStatus.SKIPPED) || abandoned(existing)) ->
                    existing
                        .apply {
                            status = SocialAiRunStatus.RUNNING
                            error = null
                            topic = null
                            postId = null
                        }.let { runs.save(it) }
                else -> throw SocialConflictException("Today's AI post for this site is already made")
            }
        } ?: throw DataIntegrityViolationException("run not claimed")

    /** A run still RUNNING long after any run ends: its node died or restarted mid-run; the admin may make it again. */
    private fun abandoned(r: SocialAiRun): Boolean = r.status == SocialAiRunStatus.RUNNING && r.updatedAt.isBefore(Instant.now().minus(RUN_ABANDONED))

    @Suppress("ReturnCount") // a guard clause per reason to skip today's post
    private fun produce(
        setting: SocialAiSetting,
        run: SocialAiRun,
        date: LocalDate,
    ): SocialAiRun {
        val site = setting.site!!
        val profile = brands.byId[site]?.ai ?: throw SocialValidationException("No site profile for $site")
        val channels = usableChannels(setting)
        if (channels.isEmpty()) return skip(run, "No connected channel is selected for this site")
        val recent = runs.findTop50ByOrderByCreatedAtDesc().filter { it.site == site && it.topic != null }
        val boat = picker.pick(profile, featuredLately(recent), date) ?: return skip(run, "No boat with photos found for this site")
        val photos =
            boat.imageIds.take(PHOTOS).mapNotNull { id ->
                runCatching {
                    store.storeFromUrl("${props.selfBaseUrl.trimEnd('/')}/public/image/$id?width=$PHOTO_WIDTH", null, SocialMediaOrigin.CATALOGUE, "yacht_image:$id")
                }.getOrNull()
            }
        if (photos.isEmpty()) return skip(run, "The boat's photos could not be loaded")

        val draft =
            writer.write(
                WriteRequest(
                    siteName = profile.siteName,
                    siteUrl = profile.baseUrl,
                    language = setting.language,
                    guidance = setting.guidance,
                    facts = boat.facts,
                    recentTopics = recent.take(RECENT_TOPICS).mapNotNull { it.topic?.substringAfter(": ", it.topic!!) },
                ),
            )
        val day = DayContext(setting, date, boat, photos, draft)
        val withVideo = setting.useVideo && props.higgsfieldConfigured
        val photoChannels = channels.filter { !(withVideo && it.platform in VIDEO_PLATFORMS) && it.platform != SocialPlatform.YOUTUBE }
        val photoPost = if (photoChannels.isEmpty()) null else createPhotoPost(day, photoChannels)
        val videoQueued = withVideo && queueVideo(day, run, channels.filter { it.platform in VIDEO_PLATFORMS }, photoPost?.id)
        return updateRun(run.id!!) {
            it.status = if (videoQueued) SocialAiRunStatus.WAITING_VIDEO else SocialAiRunStatus.DONE
            it.topic = "yacht:${boat.id} ${boat.facts["name"] ?: boat.slug}: ${draft.topic}".take(TOPIC_MAX)
            it.postId = photoPost?.id
        }
    }

    /** Everything one site's run produced before the posts: the setting, the day, the boat, our photos and the texts. */
    private data class DayContext(
        val setting: SocialAiSetting,
        val date: LocalDate,
        val boat: PickedBoat,
        val photos: List<SocialMedia>,
        val draft: AiPostDraft,
    )

    /** The site's selected channels that can publish now (its own, or shared ones such as our YouTube). */
    private fun usableChannels(setting: SocialAiSetting): List<SocialAccount> =
        accounts.findAllById(setting.accountIds).filter {
            it.disconnectedAt == null && it.status == SocialAccountStatus.CONNECTED && (it.site == setting.site || it.site == null)
        }

    /** Boats featured on this site in the last 60 days, from the runs' topics. */
    private fun featuredLately(recent: List<SocialAiRun>): Set<Long> =
        recent
            .filter { it.createdAt.isAfter(Instant.now().minus(FEATURE_PAUSE)) }
            .mapNotNull { YACHT_REF.find(it.topic!!)?.groupValues?.get(1)?.toLongOrNull() }
            .toSet()

    /** The day's photo post for the site's non-video channels, as a draft or scheduled per the site's setting. */
    private fun createPhotoPost(
        day: DayContext,
        photoChannels: List<SocialAccount>,
    ): SocialPostDto {
        val setting = day.setting
        val date = day.date
        val boat = day.boat
        val photos = day.photos
        val draft = day.draft
        val post =
            postService.create(
                SocialPostRequest(
                    text = draft.mainText,
                    link = boat.url,
                    mediaIds = photos.map { it.id!! },
                    targets =
                        photoChannels.map { a ->
                            SocialTargetRequest(
                                a.id!!,
                                textFor(a, draft),
                                if (a.platform == SocialPlatform.TIKTOK) draft.topic.take(TIKTOK_TITLE) else null,
                            )
                        },
                    action = SocialPostAction.DRAFT,
                    note = "AI $date: ${boat.facts["name"] ?: boat.slug}",
                ),
                AI_ACTOR,
                setting.site,
            )
        // Instagram refuses photos outside 4:5–1.91:1: the admin sees it on the draft, the AI only notes it.
        val offRatio = photos.count { it.width != null && it.height != null && !SocialRules.instagramRatioOk(it.width!!, it.height!!) }
        if (offRatio > 0 && post.targets.any { it.platform == SocialPlatform.INSTAGRAM }) {
            log.info("AI post {}: {} photo(s) outside Instagram's ratio", post.id, offRatio)
        }
        if (!setting.requireApproval) schedule(post.id, setting, date)
        return post
    }

    /**
     * Asks Higgsfield for a video of the best photo; pollGenerations makes the TikTok / YouTube post when it is there.
     * False when no video channel is selected (nothing to wait for).
     */
    private fun queueVideo(
        day: DayContext,
        run: SocialAiRun,
        videoChannels: List<SocialAccount>,
        photoPostId: Long?,
    ): Boolean {
        if (videoChannels.isEmpty()) return false
        val setting = day.setting
        val date = day.date
        val boat = day.boat
        val photos = day.photos
        val draft = day.draft
        val site = setting.site!!
        generations.save(
            SocialGeneration().apply {
                postId = photoPostId
                provider = "HIGGSFIELD"
                kind = SocialMediaKind.VIDEO
                prompt = draft.videoPrompt
                sourceMediaId = photos.first().id
                payload =
                    mutableMapOf(
                        "site" to site,
                        "runId" to run.id,
                        "date" to date.toString(),
                        "link" to boat.url,
                        "note" to "AI $date video: ${boat.facts["name"] ?: boat.slug}",
                        "targets" to
                            videoChannels.map { a ->
                                mapOf(
                                    "accountId" to a.id,
                                    "text" to if (a.platform == SocialPlatform.YOUTUBE) draft.youtubeDescription else draft.tiktokCaption,
                                    "title" to if (a.platform == SocialPlatform.YOUTUBE) draft.youtubeTitle else null,
                                )
                            },
                    )
            },
        )
        return true
    }

    /** Higgsfield queue: submit queued requests, poll running ones, turn finished videos into the video post. */
    @Scheduled(fixedDelayString = "\${social.ai.generation-delay-ms:60000}", initialDelayString = "\${social.ai.generation-initial-delay-ms:60000}")
    @SchedulerLock(name = "social_generations", lockAtMostFor = "PT20M")
    fun pollGenerations() {
        generations.findAllByStatusIn(listOf(SocialGenerationStatus.QUEUED, SocialGenerationStatus.RUNNING)).forEach { g ->
            runCatching { advance(g) }.onFailure { e ->
                log.warn("Higgsfield generation {} failed: {}", g.id, e.message)
                g.attempts += 1
                if (g.attempts >= MAX_GENERATION_ATTEMPTS) {
                    finishGeneration(g, failed = true, error = e.message)
                } else {
                    generations.save(g)
                }
            }
        }
    }

    @Suppress("ReturnCount") // one guard per state of the request: queued, timed out, still running, failed
    private fun advance(g: SocialGeneration) {
        if (g.status == SocialGenerationStatus.QUEUED) {
            val source = g.sourceMediaId?.let { mediaRepository.findById(it).orElse(null) } ?: error("No source photo")
            g.providerRequestId = higgsfield.submitImageToVideo(store.publicUrl(source), g.prompt.orEmpty())
            g.status = SocialGenerationStatus.RUNNING
            generations.save(g)
            return
        }
        if (g.createdAt.isBefore(Instant.now().minus(GENERATION_TIMEOUT))) {
            finishGeneration(g, failed = true, error = "Higgsfield took longer than ${GENERATION_TIMEOUT.toMinutes()} minutes")
            return
        }
        val state = higgsfield.status(g.providerRequestId ?: error("No request id"))
        if (!state.done) return
        if (state.failed || state.videoUrl == null) {
            finishGeneration(g, failed = true, error = state.error)
            return
        }
        val video = store.storeFromUrl(state.videoUrl, null, SocialMediaOrigin.GENERATED, "higgsfield:${g.providerRequestId}")
        g.resultMediaId = video.id
        @Suppress("UNCHECKED_CAST")
        val targets = (g.payload["targets"] as? List<Map<String, Any?>>).orEmpty()
        val site = g.payload["site"] as? String
        val post =
            postService.create(
                SocialPostRequest(
                    text = targets.firstOrNull()?.get("text") as? String ?: "",
                    link = g.payload["link"] as? String,
                    mediaIds = listOf(video.id!!),
                    targets = targets.map { SocialTargetRequest((it["accountId"] as Number).toLong(), it["text"] as? String, it["title"] as? String) },
                    action = SocialPostAction.DRAFT,
                    note = g.payload["note"] as? String,
                ),
                AI_ACTOR,
                site,
            )
        g.payload["videoPostId"] = post.id
        val setting = site?.let { settings.findById(it).orElse(null) }
        if (setting != null && !setting.requireApproval) {
            runCatching { schedule(post.id, setting, LocalDate.parse(g.payload["date"] as String)) }
                .onFailure { log.warn("AI video post {} not scheduled: {}", post.id, it.message) }
        }
        finishGeneration(g, failed = false, error = null)
    }

    private fun finishGeneration(
        g: SocialGeneration,
        failed: Boolean,
        error: String?,
    ) {
        g.status = if (failed) SocialGenerationStatus.FAILED else SocialGenerationStatus.DONE
        g.error = error?.take(ERROR_MAX)
        generations.save(g)
        (g.payload["runId"] as? Number)?.toLong()?.let { runId ->
            updateRun(runId) {
                it.status = SocialAiRunStatus.DONE
                if (failed) it.error = "Video: ${error ?: "failed"}".take(ERROR_MAX)
            }
        }
    }

    /** Schedules an AI draft at the site's time today (or 5 minutes from now when that time has passed). */
    private fun schedule(
        postId: Long,
        setting: SocialAiSetting,
        date: LocalDate,
    ) {
        val post = postService.get(postId)
        val at =
            ZonedDateTime.of(date, LocalTime.parse(setting.publishTime), ZONE).toInstant().let {
                if (it.isBefore(Instant.now().plusSeconds(MIN_LEAD_S))) Instant.now().plusSeconds(LATE_LEAD_S) else it
            }
        postService.update(
            postId,
            SocialPostRequest(
                text = post.text,
                link = post.link,
                mediaIds = post.media.map { it.id },
                targets = post.targets.map { SocialTargetRequest(it.accountId, it.text, it.title) },
                action = SocialPostAction.SCHEDULE,
                scheduledAt = at,
                note = post.note,
            ),
            AI_ACTOR,
        )
    }

    private fun textFor(
        a: SocialAccount,
        d: AiPostDraft,
    ): String =
        when (a.platform) {
            SocialPlatform.X, SocialPlatform.BLUESKY, SocialPlatform.THREADS -> d.shortText
            SocialPlatform.INSTAGRAM -> d.instagramCaption
            SocialPlatform.TIKTOK -> d.tiktokCaption
            SocialPlatform.YOUTUBE -> d.youtubeDescription
            else -> d.mainText
        }

    private fun skip(
        run: SocialAiRun,
        reason: String,
    ): SocialAiRun =
        updateRun(run.id!!) {
            it.status = SocialAiRunStatus.SKIPPED
            it.error = reason
        }

    private fun updateRun(
        id: Long,
        change: (SocialAiRun) -> Unit,
    ): SocialAiRun =
        tx.execute {
            val r = runs.findById(id).orElseThrow()
            change(r)
            runs.save(r)
        }!!

    private fun settingDto(
        site: String,
        s: SocialAiSetting?,
    ) = SocialAiSettingDto(
        site = site,
        siteName = brands.byId[site]?.ai?.siteName ?: site,
        enabled = s?.enabled ?: false,
        publishTime = s?.publishTime ?: DEFAULT_TIME,
        language = s?.language ?: "en",
        accountIds = s?.accountIds?.toList() ?: emptyList(),
        requireApproval = s?.requireApproval ?: true,
        useVideo = s?.useVideo ?: false,
        guidance = s?.guidance,
        updatedAt = s?.updatedAt,
    )

    private fun runDto(r: SocialAiRun) = SocialAiRunDto(r.id!!, r.site, r.runDate!!, r.status, r.topic, r.postId, r.error, r.createdAt)

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Zagreb")
        val AI_ACTOR = SocialActor(userId = null, name = "AI", source = SocialPostSource.AI)
        private val VIDEO_PLATFORMS = setOf(SocialPlatform.TIKTOK, SocialPlatform.YOUTUBE)
        private val TIME = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")
        private val LANGUAGES = setOf("en", "hr", "de", "it", "fr", "es", "pl", "nl")
        private val YACHT_REF = Regex("""yacht:(\d+)""")
        private val LEAD: Duration = Duration.ofHours(3)
        private val EARLIEST: LocalTime = LocalTime.of(6, 0)
        private val FEATURE_PAUSE: Duration = Duration.ofDays(60)
        private val GENERATION_TIMEOUT: Duration = Duration.ofMinutes(45)
        private val RUN_ABANDONED: Duration = Duration.ofMinutes(30)
        private const val DEFAULT_TIME = "18:00"
        private const val PHOTOS = 4
        private const val PHOTO_WIDTH = 2160
        private const val RECENT_TOPICS = 10
        private const val TOPIC_MAX = 300
        private const val ERROR_MAX = 1000
        private const val GUIDANCE_MAX = 4000
        private const val TIKTOK_TITLE = 90
        private const val MIN_LEAD_S = 120L
        private const val LATE_LEAD_S = 300L
        private const val MAX_GENERATION_ATTEMPTS = 5
    }
}
