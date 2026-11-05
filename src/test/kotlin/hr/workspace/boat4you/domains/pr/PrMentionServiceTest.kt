package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.PrJpaTest.TestClock
import hr.workspace.boat4you.domains.pr.dto.PrAlertFeedRequest
import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeed
import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeedRepository
import hr.workspace.boat4you.domains.pr.jpa.PrMention
import hr.workspace.boat4you.domains.pr.jpa.PrMentionRepository
import hr.workspace.boat4you.domains.pr.jpa.PrMentionStatus
import hr.workspace.boat4you.domains.pr.links.FetchResult
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import hr.workspace.boat4you.domains.pr.mentions.PrMentionService
import hr.workspace.boat4you.domains.pr.service.PrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * Mentions from Google Alerts / Talkwalker feeds against the real V9_121 schema. The fetcher is a Mockito mock that
 * answers from [pages] and records every URL asked for (no network); "now" is a TestClock.
 */
class PrMentionServiceTest : PrJpaTest() {
    @Autowired private lateinit var service: PrMentionService

    @Autowired private lateinit var fetcher: PrPageFetcher

    @Autowired private lateinit var clock: TestClock

    @Autowired private lateinit var feeds: PrAlertFeedRepository

    @Autowired private lateinit var mentions: PrMentionRepository

    private val now = Instant.parse("2026-11-05T05:15:00Z")
    private val pages = mutableMapOf<String, FetchResult>()
    private val fetched = mutableListOf<String>()
    private val sleeps = mutableListOf<Long>()

    private val googleFeed = "https://www.google.com/alerts/feeds/04170551338912345678/1385722190812345678"
    private val talkwalkerFeed = "https://alerts.talkwalker.com/alerts/rss/ABCDEF0123456789"
    private val exampleNews = "https://www.example-news.co.uk/travel/Croatia-Charter-Prices?id=7"
    private val travelwire = "https://travelwire.example/2026/11/sailing-holiday-costs"
    private val digest = "https://www.yachting-digest.example/news/greek-fleet-report?page=2"

    private fun fixture(name: String) = ClassPathResource("pr/$name").inputStream.readAllBytes().decodeToString()

    @BeforeEach
    fun setUp() {
        clock.now = now
        pages.clear()
        fetched.clear()
        sleeps.clear()
        reset(fetcher)
        `when`(fetcher.fetch(anyString())).thenAnswer { inv ->
            val url = inv.getArgument<String>(0)
            fetched += url
            pages[url] ?: throw IllegalStateException("unexpected fetch $url")
        }
        service.sleeper = { sleeps += it }
        service.maxPagesPerRun = 50
    }

    private fun page(
        url: String,
        status: Int,
        html: String? = null,
    ) {
        pages[url] = FetchResult(status, url, html, null)
    }

    private fun feed(
        url: String,
        name: String = "Feed",
        enabled: Boolean = true,
        lastError: String? = null,
    ): Long =
        feeds
            .save(
                PrAlertFeed().apply {
                    this.name = name
                    this.url = url
                    this.enabled = enabled
                    this.lastError = lastError
                },
            ).id!!

    private fun articlePages() {
        page(exampleNews, 200, fixture("article-follow.html"))
        page(travelwire, 200, fixture("article-no-link.html"))
        page(digest, 403)
    }

    @Test
    fun newMentionsGetLinkCheckSourceAndStatus() {
        val g = feed(googleFeed, "Google: boat4you")
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        val t = feed(talkwalkerFeed, "Talkwalker: boat4you")
        page(talkwalkerFeed, 200, fixture("talkwalker.rss.xml"))
        articlePages()

        service.ingestAll() shouldBe 3

        mentions.findAll().map { listOf(it.url, it.title, it.source, it.feedId, it.linksToUs, it.status, it.foundAt) } shouldContainExactlyInAnyOrder
            listOf(
                listOf(exampleNews, "Croatia yacht charter prices up 8%, says Boat4you study", "GOOGLE_ALERTS", g, true, PrMentionStatus.NEW, now),
                listOf(travelwire, "A skipper's view: what a sailing week costs & why (Boat4you)", "GOOGLE_ALERTS", g, false, PrMentionStatus.NEW, now),
                // 403: the page refused us, so whether it links is unknown (null), not "no".
                listOf(digest, "Boat4you & the Greek islands: fleet report", "TALKWALKER", t, null, PrMentionStatus.NEW, now),
            )
        feeds.findAll().forEach {
            it.lastFetchedAt shouldBe now
            it.lastError shouldBe null
        }
    }

    @Test
    fun goneArticleDoesNotLinkAndOtherFeedsAreRss() {
        val f = feed("https://feeds.example-news.co.uk/alerts.rss")
        page("https://feeds.example-news.co.uk/alerts.rss", 200, fixture("talkwalker.rss.xml"))
        page(exampleNews, 404)
        page(digest, 503)

        service.ingestAll() shouldBe 2

        mentions.findAll().associate { it.url to (it.source to it.linksToUs) } shouldBe
            mapOf(exampleNews to ("RSS" to false), digest to ("RSS" to null))
        mentions.findAll().all { it.feedId == f } shouldBe true
    }

    @Test
    fun existingMentionNotDuplicated() {
        mentions.save(
            PrMention().apply {
                url = travelwire
                source = "RSS"
                status = PrMentionStatus.IGNORED
                foundAt = now.minusSeconds(86_400)
            },
        )
        feed(googleFeed)
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        feed(talkwalkerFeed)
        page(talkwalkerFeed, 200, fixture("talkwalker.rss.xml"))
        articlePages()

        // The same article via the Google redirect and directly from Talkwalker is one mention, its page fetched once.
        service.ingestAll() shouldBe 2
        mentions.findAll().map { it.url } shouldContainExactlyInAnyOrder listOf(travelwire, exampleNews, digest)
        mentions.findAll().single { it.url == travelwire }.status shouldBe PrMentionStatus.IGNORED
        fetched shouldContainExactly listOf(googleFeed, exampleNews, talkwalkerFeed, digest)

        // The next run finds nothing new and fetches no article again.
        fetched.clear()
        service.ingestAll() shouldBe 0
        fetched shouldContainExactly listOf(googleFeed, talkwalkerFeed)
        mentions.count() shouldBe 3
    }

    @Test
    fun ourOwnDomainsAreNotMentions() {
        feed(googleFeed)
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        feed(talkwalkerFeed)
        page(talkwalkerFeed, 200, fixture("talkwalker.rss.xml"))
        articlePages()

        service.ingestAll()

        // blog.catamaran-croatia-charter.com (via Google) and www.boat4you.com (Talkwalker) are ours: never stored nor fetched.
        mentions.findAll().map { it.url } shouldContainExactlyInAnyOrder listOf(exampleNews, travelwire, digest)
        fetched.none { "boat4you.com" in it || "catamaran-croatia-charter.com" in it } shouldBe true
    }

    @Test
    fun brokenFeedDoesNotStopOthers() {
        val down = feed("https://alerts.talkwalker.com/alerts/rss/DOWN", lastError = null)
        page("https://alerts.talkwalker.com/alerts/rss/DOWN", 500)
        val unreachable = feed("https://feeds.unreachable.example/rss")
        page("https://feeds.unreachable.example/rss", -1)
        val xxe = feed("https://feeds.example/xxe.rss")
        page("https://feeds.example/xxe.rss", 200, fixture("xxe.rss.xml"))
        val html = feed("https://feeds.example/not-a-feed")
        page("https://feeds.example/not-a-feed", 200, fixture("article-follow.html"))
        val good = feed(googleFeed, lastError = "HTTP 500")
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        feed("https://feeds.example/disabled", enabled = false)
        articlePages()

        service.ingestAll() shouldBe 2

        val byId = feeds.findAll().associateBy { it.id }
        byId[down]!!.lastError shouldBe "HTTP 500"
        byId[unreachable]!!.lastError shouldBe "Feed not fetched (blocked address, timeout or network error)"
        byId[xxe]!!.lastError!! shouldContain "DOCTYPE"
        byId[html]!!.lastError!! shouldContain "Not an RSS 2.0 or Atom feed"
        listOf(down, unreachable, xxe, html).forEach { byId[it]!!.lastFetchedAt shouldBe null }
        byId[good]!!.lastError shouldBe null
        byId[good]!!.lastFetchedAt shouldBe now
        // Nothing behind the XXE feed was fetched (no entity, no item), and the disabled feed was not asked for.
        fetched shouldContainExactly
            listOf(
                "https://alerts.talkwalker.com/alerts/rss/DOWN",
                "https://feeds.unreachable.example/rss",
                "https://feeds.example/xxe.rss",
                "https://feeds.example/not-a-feed",
                googleFeed,
                exampleNews,
                travelwire,
            )
        mentions.findAll().map { it.url } shouldContainExactlyInAnyOrder listOf(exampleNews, travelwire)
    }

    @Test
    fun pageLimitLeavesTheRestForTheNextRun() {
        feed(googleFeed)
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        feed(talkwalkerFeed)
        page(talkwalkerFeed, 200, fixture("talkwalker.rss.xml"))
        articlePages()
        service.maxPagesPerRun = 1

        // Items not reached are not stored: the feed still lists them next time.
        service.ingestAll() shouldBe 1
        mentions.findAll().map { it.url } shouldContainExactly listOf(exampleNews)
        service.ingestAll() shouldBe 1
        service.ingestAll() shouldBe 1
        mentions.findAll().map { it.url } shouldContainExactlyInAnyOrder listOf(exampleNews, travelwire, digest)
    }

    @Test
    fun pausesBetweenTwoRequestsToOneHost() {
        feed(googleFeed)
        page(googleFeed, 200, fixture("google-alerts.atom.xml"))
        feed("https://www.google.com/alerts/feeds/04170551338912345678/2")
        page("https://www.google.com/alerts/feeds/04170551338912345678/2", 200, fixture("google-alerts.atom.xml"))
        articlePages()

        service.ingestAll() shouldBe 2

        // Two google.com feeds back to back: one pause before the second; the two articles are on different hosts.
        sleeps.size shouldBe 1
        (sleeps.single() in 1..2_000L) shouldBe true
    }

    @Test
    fun feedsAreManagedByTheAdmin() {
        val created = service.createFeed(PrAlertFeedRequest(name = " Google: boat4you ", url = " $googleFeed "))
        created.name shouldBe "Google: boat4you"
        created.url shouldBe googleFeed
        created.enabled shouldBe true

        shouldThrow<PrException> { service.createFeed(PrAlertFeedRequest(name = "Again", url = googleFeed)) }.let {
            it.status shouldBe HttpStatus.CONFLICT
            it.code shouldBe "FEED_EXISTS"
        }
        listOf(null, "", "ftp://feeds.example/a", "file:///etc/passwd", "https://feeds.example/" + "a".repeat(1000)).forEach { url ->
            shouldThrow<PrException> { service.createFeed(PrAlertFeedRequest(name = "X", url = url)) }.code shouldBe "FEED_URL_INVALID"
        }
        shouldThrow<PrException> { service.createFeed(PrAlertFeedRequest(name = " ", url = talkwalkerFeed)) }.code shouldBe "FEED_NAME_REQUIRED"

        val tw = service.createFeed(PrAlertFeedRequest(name = "Talkwalker", url = talkwalkerFeed, enabled = false))
        tw.enabled shouldBe false
        shouldThrow<PrException> { service.updateFeed(tw.id, PrAlertFeedRequest(url = googleFeed)) }.code shouldBe "FEED_EXISTS"
        service.updateFeed(tw.id, PrAlertFeedRequest(enabled = true)).let {
            it.enabled shouldBe true
            it.name shouldBe "Talkwalker"
        }
        jdbc.update("UPDATE pr_alert_feed SET last_error = 'HTTP 500', last_fetched_at = now() WHERE id = ?", tw.id)
        service.updateFeed(tw.id, PrAlertFeedRequest(url = "https://alerts.talkwalker.com/alerts/rss/NEW")).let {
            it.url shouldBe "https://alerts.talkwalker.com/alerts/rss/NEW"
            it.lastError shouldBe null
            it.lastFetchedAt shouldBe null
        }
        shouldThrow<PrException> { service.updateFeed(999, PrAlertFeedRequest(enabled = false)) }.code shouldBe "FEED_NOT_FOUND"
        service.listFeeds().map { it.name } shouldContainExactly listOf("Google: boat4you", "Talkwalker")
    }

    @Test
    fun mentionsListFiltersAndStatus() {
        val f = feed(googleFeed, "Google: boat4you")

        fun mention(
            url: String,
            linksToUs: Boolean?,
            status: PrMentionStatus,
            hoursAgo: Long,
        ) = mentions
            .save(
                PrMention().apply {
                    this.url = url
                    source = "GOOGLE_ALERTS"
                    feedId = f
                    this.linksToUs = linksToUs
                    this.status = status
                    foundAt = now.minusSeconds(hoursAgo * 3600)
                },
            ).id!!
        val a = mention("https://news.example/a", true, PrMentionStatus.NEW, 3)
        val b = mention("https://news.example/b", false, PrMentionStatus.NEW, 1)
        val c = mention("https://news.example/c", null, PrMentionStatus.IGNORED, 2)

        service.listMentions(null, null, 0, 50).let { page ->
            page.content.map { it.id } shouldContainExactly listOf(b, c, a)
            page.content.first().feedName shouldBe "Google: boat4you"
        }
        service.listMentions(PrMentionStatus.NEW, null, 0, 50).content.map { it.id } shouldContainExactly listOf(b, a)
        service.listMentions(null, false, 0, 50).content.map { it.id } shouldContainExactly listOf(b)
        service.listMentions(PrMentionStatus.NEW, true, 0, 50).content.map { it.id } shouldContainExactly listOf(a)
        service.listMentions(null, null, 1, 2).let {
            it.content.map { m -> m.id } shouldContainExactly listOf(a)
            it.totalElements shouldBe 3
        }

        service.setMentionStatus(b, PrMentionStatus.ASK_FOR_LINK).status shouldBe PrMentionStatus.ASK_FOR_LINK
        mentions.findById(b).get().status shouldBe PrMentionStatus.ASK_FOR_LINK
        shouldThrow<PrException> { service.setMentionStatus(999, PrMentionStatus.IGNORED) }.code shouldBe "MENTION_NOT_FOUND"
    }
}
