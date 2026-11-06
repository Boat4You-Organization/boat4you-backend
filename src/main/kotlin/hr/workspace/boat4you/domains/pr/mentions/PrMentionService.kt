package hr.workspace.boat4you.domains.pr.mentions

import hr.workspace.boat4you.domains.pr.dto.PrAlertFeedDto
import hr.workspace.boat4you.domains.pr.dto.PrAlertFeedRequest
import hr.workspace.boat4you.domains.pr.dto.PrMentionDto
import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeed
import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeedRepository
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrMention
import hr.workspace.boat4you.domains.pr.jpa.PrMentionRepository
import hr.workspace.boat4you.domains.pr.jpa.PrMentionStatus
import hr.workspace.boat4you.domains.pr.links.FetchResult
import hr.workspace.boat4you.domains.pr.links.PrLinkInspector
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrProperties
import jakarta.persistence.criteria.Predicate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.Locale

/**
 * Mentions of us from Google Alerts / Talkwalker / other RSS or Atom feeds (pr_alert_feed), and the admin's feed and
 * mention lists. Every 6 h each enabled feed is fetched (PrPageFetcher) and parsed (PrFeedParser); an item becomes a
 * NEW pr_mention unless its normalised URL is one of our sites or already stored. Its page is fetched once to set
 * links_to_us (PrLinkInspector: a link of ours = true; no link or 404 / 410 = false; refused or not fetched = null).
 * A run fetches at most [maxPagesPerRun] article pages within [runBudget], pausing [PAUSE_MS] between two requests to
 * one host; items it does not reach are not stored (the feed still lists them next run). A feed that cannot be fetched
 * or parsed gets last_error and the run goes on with the next one. No DB transaction is held while a page is fetched.
 */
@Service
class PrMentionService(
    private val feeds: PrAlertFeedRepository,
    private val mentions: PrMentionRepository,
    private val fetcher: PrPageFetcher,
    private val props: PrProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(PrMentionService::class.java)

    // Test seams (the project's `internal var` pattern, as PrLinkCheckService).
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }
    internal var maxPagesPerRun: Int = MAX_PAGES_PER_RUN

    /** Stops a run well before the 20-minute lock can expire; the rest waits for the next run. */
    internal var runBudget: Duration = Duration.ofMinutes(15)

    /** One run over every enabled feed (by id). Returns how many mentions were stored. */
    fun ingestAll(): Int {
        val run = Run()
        val enabled = feeds.findAllByEnabledTrueOrderByIdAsc()
        for ((i, feed) in enabled.withIndex()) {
            if (run.exhausted()) {
                log.warn("PR mentions: run limit reached ({} pages, {}), {} feed(s) left for the next run", run.pages, runBudget, enabled.size - i)
                break
            }
            try {
                ingestFeed(feed, run)
            } catch (e: Exception) {
                log.error("PR mentions: feed {} failed", feed.id, e)
                runCatching { feeds.recordError(feed.id!!, "Failed: ${e.javaClass.simpleName}") }
            }
        }
        log.info("PR mentions: {} new mention(s) from {} feed(s), {} page(s) fetched", run.inserted, enabled.size, run.pages)
        return run.inserted
    }

    @Scheduled(cron = "\${pr.mentions.cron:0 15 */6 * * *}", zone = "Europe/Zagreb")
    @SchedulerLock(name = "pr_mentions", lockAtMostFor = "PT20M")
    fun ingestAllScheduled() {
        runCatching { ingestAll() }.onFailure { log.error("PR mentions run failed", it) }
    }

    // ---- Admin: feeds ----

    @Transactional(readOnly = true)
    fun listFeeds(): List<PrAlertFeedDto> = feeds.findAll(Sort.by("id")).map { it.toDto() }

    @Transactional
    fun createFeed(req: PrAlertFeedRequest): PrAlertFeedDto {
        val name = feedName(req.name)
        val url = feedUrl(req.url)
        if (feeds.findFirstByUrl(url) != null) throw feedExists(url)
        val feed =
            PrAlertFeed().apply {
                this.name = name
                this.url = url
                enabled = req.enabled ?: true
                createdAt = clock.instant()
            }
        return feeds.save(feed).toDto()
    }

    /** Only the fields sent change; a new URL is another feed, so its last read and error are cleared. */
    @Transactional
    fun updateFeed(
        id: Long,
        req: PrAlertFeedRequest,
    ): PrAlertFeedDto {
        val feed = feeds.findById(id).orElseThrow { PrException.notFound("FEED_NOT_FOUND", "Feed $id not found") }
        val name = req.name?.let { feedName(it) }
        val url = req.url?.let { feedUrl(it) }
        // Checked before any field changes: a query would flush a clashing change into a unique-index error.
        if (url != null && url != feed.url) {
            val clash = feeds.findFirstByUrl(url)
            if (clash != null && clash.id != feed.id) throw feedExists(url)
            feed.url = url
            feed.lastFetchedAt = null
            feed.lastError = null
        }
        name?.let { feed.name = it }
        req.enabled?.let { feed.enabled = it }
        return feeds.save(feed).toDto()
    }

    // ---- Admin: mentions ----

    /** Newest first (found_at, then id); [status] and [linksToUs] filter when given. */
    @Transactional(readOnly = true)
    fun listMentions(
        status: PrMentionStatus?,
        linksToUs: Boolean?,
        page: Int,
        size: Int,
    ): Page<PrMentionDto> {
        val spec =
            Specification<PrMention> { root, _, cb ->
                val where = mutableListOf<Predicate>()
                status?.let { where += cb.equal(root.get<PrMentionStatus>("status"), it) }
                linksToUs?.let { where += cb.equal(root.get<Boolean>("linksToUs"), it) }
                cb.and(*where.toTypedArray())
            }
        val sort = Sort.by(Sort.Order.desc("foundAt"), Sort.Order.desc("id"))
        val rows = mentions.findAll(spec, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), sort))
        val names = feeds.findAllById(rows.content.mapNotNull { it.feedId }.toSet()).associate { it.id to it.name }
        return rows.map { it.toDto(names[it.feedId]) }
    }

    @Transactional
    fun setMentionStatus(
        id: Long,
        status: PrMentionStatus,
    ): PrMentionDto {
        val mention = mentions.findById(id).orElseThrow { PrException.notFound("MENTION_NOT_FOUND", "Mention $id not found") }
        mention.status = status
        val saved = mentions.save(mention)
        return saved.toDto(saved.feedId?.let { feeds.findById(it).orElse(null)?.name })
    }

    // ---- Ingest ----

    /** The limits and per-host pacing of one run. */
    private inner class Run {
        private val deadline = System.nanoTime() + runBudget.toNanos()
        private val lastDone = mutableMapOf<String, Long>()
        var pages = 0
        var inserted = 0

        fun exhausted() = pages >= maxPagesPerRun || System.nanoTime() > deadline

        fun fetch(url: String): FetchResult {
            val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
            host?.let { lastDone[it] }?.let { last ->
                val wait = PAUSE_MS - (System.nanoTime() - last) / NANOS_PER_MS
                if (wait > 0) sleeper(wait)
            }
            try {
                return fetcher.fetch(url)
            } finally {
                host?.let { lastDone[it] = System.nanoTime() }
            }
        }
    }

    private fun ingestFeed(
        feed: PrAlertFeed,
        run: Run,
    ) {
        val id = feed.id!!
        val fetch = run.fetch(feed.url)
        val xml = fetch.html?.takeIf { fetch.status in 200..299 }
        if (xml == null) return feedFailed(id, if (fetch.status == -1) NOT_FETCHED else "HTTP ${fetch.status}")
        val items =
            try {
                PrFeedParser.parse(xml)
            } catch (e: IllegalArgumentException) {
                return feedFailed(id, "Not an RSS 2.0 or Atom feed: ${e.message}")
            }
        val source = sourceOf(feed.url)
        for (item in items) {
            val url = runCatching { PrFeedParser.normaliseUrl(item.url) }.getOrNull()?.takeIf { it.length <= URL_MAX } ?: continue
            if (PrLinkInspector.isOurUrl(url, props.ourDomains) || mentions.existsByUrl(url)) continue
            if (run.exhausted()) break
            val page = run.fetch(url)
            run.pages++
            if (store(url, item.title, source, id, linksToUs(page))) run.inserted++
        }
        feeds.recordSuccess(id, clock.instant())
    }

    private fun feedFailed(
        id: Long,
        error: String,
    ) {
        log.warn("PR mentions: feed {}: {}", id, error)
        feeds.recordError(id, error.take(ERROR_MAX))
    }

    /** true / false from the page; null when it refused us or was not fetched (BLOCKED_UNKNOWN). */
    private fun linksToUs(page: FetchResult): Boolean? {
        val inspection = page.html?.takeIf { page.status in 200..299 }?.let { PrLinkInspector.inspect(it, props.ourDomains, page.finalUrl) }
        return when (PrLinkInspector.classify(page, inspection)) {
            PrLinkState.BLOCKED_UNKNOWN -> null
            PrLinkState.OK_FOLLOW, PrLinkState.OK_NOFOLLOW -> true
            else -> false
        }
    }

    /** false when the URL got stored meanwhile (unique index). */
    private fun store(
        url: String,
        title: String,
        source: String,
        feedId: Long,
        linksToUs: Boolean?,
    ): Boolean {
        val now = clock.instant()
        val mention =
            PrMention().apply {
                this.url = url
                this.title = title.ifEmpty { null }
                this.source = source
                this.feedId = feedId
                this.linksToUs = linksToUs
                foundAt = now
                createdAt = now
            }
        return try {
            mentions.save(mention)
            true
        } catch (e: DataIntegrityViolationException) {
            false
        }
    }

    private fun feedName(raw: String?): String = raw?.trim()?.take(NAME_MAX)?.ifEmpty { null } ?: throw PrException.badRequest("FEED_NAME_REQUIRED", "Feed name is required")

    /** Trimmed, otherwise as typed (feed URLs carry case-sensitive tokens): http(s) with a host, no credentials, at most 1000 characters. */
    private fun feedUrl(raw: String?): String {
        val invalid = PrException.badRequest("FEED_URL_INVALID", "Feed URL must be a full http(s) address of at most $URL_MAX characters")
        val url = raw?.trim().orEmpty()
        val uri = runCatching { URI(url) }.getOrElse { throw invalid }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || url.length > URL_MAX) throw invalid
        return url
    }

    private fun feedExists(url: String) = PrException.conflict("FEED_EXISTS", "A feed for $url already exists")

    private fun PrAlertFeed.toDto() = PrAlertFeedDto(id!!, name, url, enabled, lastFetchedAt, lastError, createdAt)

    private fun PrMention.toDto(feedName: String?) = PrMentionDto(id!!, url, title, source, feedId, feedName, linksToUs, status, foundAt)

    companion object {
        const val MAX_PAGES_PER_RUN = 50
        private const val PAUSE_MS = 2_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val URL_MAX = 1000
        private const val NAME_MAX = 200
        private const val ERROR_MAX = 300
        private const val MAX_PAGE_SIZE = 200
        private const val NOT_FETCHED = "Feed not fetched (blocked address, timeout or network error)"

        /** GOOGLE_ALERTS for a google.com feed, TALKWALKER for talkwalker.com (subdomains included), RSS otherwise. */
        fun sourceOf(feedUrl: String): String {
            val host = runCatching { URI(feedUrl.trim()).host?.lowercase(Locale.ROOT)?.trimEnd('.') }.getOrNull().orEmpty()

            fun on(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                on("google.com") -> "GOOGLE_ALERTS"
                on("talkwalker.com") -> "TALKWALKER"
                else -> "RSS"
            }
        }
    }
}
