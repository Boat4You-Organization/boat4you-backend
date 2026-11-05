package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.PrPitchDraftTest.TestClock
import hr.workspace.boat4you.domains.pr.dto.PrPlacementRequest
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPlacement
import hr.workspace.boat4you.domains.pr.jpa.PrPlacementRepository
import hr.workspace.boat4you.domains.pr.jpa.PrStudy
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.links.FetchResult
import hr.workspace.boat4you.domains.pr.links.PrLinkCheckService
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import hr.workspace.boat4you.domains.pr.service.OpenPageRankClient
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Placements and their link checks against the real V9_121 schema. The page fetcher and Open PageRank are Mockito
 * mocks (no network); "now" is a TestClock. Alert rule (R31), against last_good_state: OK_FOLLOW -> LINK_MISSING /
 * PAGE_GONE / OK_NOFOLLOW, or OK_NOFOLLOW -> LINK_MISSING / PAGE_GONE; BLOCKED_UNKNOWN never alerts nor changes
 * last_good_state; an alert is cleared only by hand, which also acknowledges the loss.
 */
@Import(PrPlacementService::class, PrLinkCheckService::class, PrLinkCheckServiceTest.Beans::class)
class PrLinkCheckServiceTest : PrJpaTest() {
    @TestConfiguration
    class Beans {
        @Bean
        fun clock() = TestClock(Instant.parse("2026-11-05T04:30:00Z"))

        @Bean
        fun prPageFetcher(): PrPageFetcher = mock(PrPageFetcher::class.java)

        @Bean
        fun openPageRankClient(): OpenPageRankClient = mock(OpenPageRankClient::class.java)
    }

    @Autowired private lateinit var service: PrLinkCheckService

    @Autowired private lateinit var placementService: PrPlacementService

    @Autowired private lateinit var fetcher: PrPageFetcher

    @Autowired private lateinit var opr: OpenPageRankClient

    @Autowired private lateinit var clock: TestClock

    @Autowired private lateinit var placements: PrPlacementRepository

    @Autowired private lateinit var studies: PrStudyRepository

    @Autowired private lateinit var outlets: PrOutletRepository

    private val now = Instant.parse("2026-11-05T04:30:00Z")
    private val today = LocalDate.of(2026, 11, 5)
    private val sleeps = mutableListOf<Long>()
    private val pages = mutableMapOf<String, FetchResult>()

    private fun fixture(name: String) = ClassPathResource("pr/$name").inputStream.readAllBytes().decodeToString()

    @BeforeEach
    fun setUp() {
        clock.now = now
        sleeps.clear()
        pages.clear()
        reset(fetcher, opr)
        `when`(fetcher.fetch(anyString())).thenAnswer { inv ->
            val url = inv.getArgument<String>(0)
            pages[url] ?: throw IllegalStateException("unexpected fetch $url")
        }
        service.sleeper = { sleeps += it }
        service.maxPerRun = 200
    }

    private fun page(
        url: String,
        status: Int,
        html: String? = null,
        finalUrl: String = url,
        xRobotsTag: String? = null,
    ) {
        pages[url] = FetchResult(status, finalUrl, html, xRobotsTag)
    }

    private fun placement(
        url: String = "https://www.example-news.co.uk/travel/a",
        state: PrLinkState = PrLinkState.NOT_CHECKED,
        publishedOn: LocalDate? = today.minusDays(3),
        lastCheckedAt: Instant? = null,
        lastGood: PrLinkState? = state.takeIf { it == PrLinkState.OK_FOLLOW || it == PrLinkState.OK_NOFOLLOW },
    ): Long =
        placements
            .save(
                PrPlacement().apply {
                    articleUrl = url
                    linkState = state
                    lastGoodState = lastGood
                    this.publishedOn = publishedOn
                    this.lastCheckedAt = lastCheckedAt
                },
            ).id!!

    private fun PrException.shouldBeError(
        status: HttpStatus,
        code: String,
    ) {
        this.status shouldBe status
        this.code shouldBe code
    }

    // ---- check ----

    @Test
    fun forbiddenPageIsBlockedUnknownNotMissing() {
        val id = placement(state = PrLinkState.OK_FOLLOW)
        page("https://www.example-news.co.uk/travel/a", 403, "<html>no bots</html>")

        val dto = service.check(id)

        dto.linkState shouldBe PrLinkState.BLOCKED_UNKNOWN
        dto.alert shouldBe false
        dto.alertReason shouldBe null
        dto.lastHttpStatus shouldBe 403
        dto.lastCheckedAt shouldBe now
        listOf(402, 429, 503, 401, -1).forEach { status ->
            val other = placement(url = "https://n.example/$status", state = PrLinkState.OK_FOLLOW)
            page("https://n.example/$status", status)
            service.check(other).let {
                it.linkState shouldBe PrLinkState.BLOCKED_UNKNOWN
                it.alert shouldBe false
                it.lastHttpStatus shouldBe status
            }
        }
    }

    @Test
    fun goneIs404Or410() {
        val a = placement(url = "https://n.example/a")
        val b = placement(url = "https://n.example/b")
        page("https://n.example/a", 404)
        page("https://n.example/b", 410)

        service.check(a).linkState shouldBe PrLinkState.PAGE_GONE
        service.check(b).linkState shouldBe PrLinkState.PAGE_GONE
        placements.findById(b).get().lastHttpStatus shouldBe 410
    }

    @Test
    fun dropFromFollowToMissingRaisesAlert() {
        val url = "https://www.example-news.co.uk/travel/a"
        val id = placement(url = url)
        page(url, 200, fixture("article-follow.html"))
        service.check(id).let {
            it.linkState shouldBe PrLinkState.OK_FOLLOW
            it.lastGoodState shouldBe PrLinkState.OK_FOLLOW
            it.alert shouldBe false
        }

        page(url, 200, fixture("article-no-link.html"))
        clock.now = now.plusSeconds(86_400)
        val dropped = service.check(id)

        dropped.linkState shouldBe PrLinkState.LINK_MISSING
        dropped.alert shouldBe true
        dropped.alertReason shouldBe "OK_FOLLOW→LINK_MISSING"
        dropped.lastCheckedAt shouldBe now.plusSeconds(86_400)

        // The link comes back: the state follows, the alert stays until someone clears it.
        page(url, 200, fixture("article-follow.html"))
        service.check(id).let {
            it.linkState shouldBe PrLinkState.OK_FOLLOW
            it.alert shouldBe true
            it.alertReason shouldBe "OK_FOLLOW→LINK_MISSING"
        }
        placementService.clearAlert(id).let {
            it.alert shouldBe false
            it.alertReason shouldBe null
            it.linkState shouldBe PrLinkState.OK_FOLLOW
            it.lastGoodState shouldBe PrLinkState.OK_FOLLOW
        }
    }

    @Test
    fun followToBlockedToMissingStillAlerts() {
        val url = "https://www.example-news.co.uk/travel/a"
        val id = placement(url = url, state = PrLinkState.OK_FOLLOW)

        page(url, 403)
        service.check(id).let {
            it.linkState shouldBe PrLinkState.BLOCKED_UNKNOWN
            it.alert shouldBe false
        }
        page(url, 200, fixture("article-no-link.html"))
        service.check(id).let {
            it.linkState shouldBe PrLinkState.LINK_MISSING
            it.alert shouldBe true
            it.alertReason shouldBe "OK_FOLLOW\u2192LINK_MISSING"
        }
    }

    @Test
    fun alertClearedDuringTheFetchIsNotReRaised() {
        val url = "https://www.example-news.co.uk/travel/a"
        val id = placement(url = url, state = PrLinkState.OK_FOLLOW)
        page(url, 200, fixture("article-no-link.html"))
        service.check(id).alert shouldBe true
        // The admin presses "Ukloni upozorenje" while the next check is still fetching the page.
        `when`(fetcher.fetch(url)).thenAnswer {
            placementService.clearAlert(id)
            FetchResult(200, url, fixture("article-no-link.html"), null)
        }

        service.check(id).let {
            it.alert shouldBe false
            it.lastGoodState shouldBe PrLinkState.LINK_MISSING
        }
    }

    @Test
    fun blockedNeverAlertsOrChangesLastGood() {
        listOf(PrLinkState.OK_FOLLOW, PrLinkState.OK_NOFOLLOW, null).forEachIndexed { i, lastGood ->
            val url = "https://n.example/blocked-$i"
            val id = placement(url = url, state = PrLinkState.LINK_MISSING, lastGood = lastGood)
            listOf(403, 429, -1).forEach { status ->
                page(url, status)
                service.check(id).let {
                    it.linkState shouldBe PrLinkState.BLOCKED_UNKNOWN
                    it.lastHttpStatus shouldBe status
                    it.lastCheckedAt shouldBe now
                    it.lastGoodState shouldBe lastGood
                    it.alert shouldBe false
                    it.alertReason shouldBe null
                }
            }
        }
    }

    @Test
    fun clearedLossIsNotReRaised() {
        val url = "https://www.example-news.co.uk/travel/a"
        val id = placement(url = url, state = PrLinkState.OK_FOLLOW)
        page(url, 200, fixture("article-no-link.html"))
        service.check(id).alert shouldBe true

        placementService.clearAlert(id).let {
            it.alert shouldBe false
            it.lastGoodState shouldBe PrLinkState.LINK_MISSING
        }
        service.check(id).alert shouldBe false
        // Gone after the acknowledged loss: still no alert (nothing good to lose)...
        page(url, 404)
        service.check(id).alert shouldBe false
        // ...until the link comes back and drops again.
        page(url, 200, fixture("article-follow.html"))
        service.check(id).lastGoodState shouldBe PrLinkState.OK_FOLLOW
        page(url, 200, fixture("article-nofollow.html"))
        service.check(id).let {
            it.alert shouldBe true
            it.alertReason shouldBe "OK_FOLLOW\u2192OK_NOFOLLOW"
            it.lastGoodState shouldBe PrLinkState.OK_NOFOLLOW
        }
        // An acknowledged OK_NOFOLLOW still alerts when that link goes too.
        placementService.clearAlert(id).lastGoodState shouldBe PrLinkState.OK_NOFOLLOW
        page(url, 410)
        service.check(id).alertReason shouldBe "OK_NOFOLLOW\u2192PAGE_GONE"
    }

    @Test
    fun alertOnlyOnTheDropsTheRuleNames() {
        val html =
            mapOf(
                PrLinkState.OK_FOLLOW to (200 to fixture("article-follow.html")),
                PrLinkState.OK_NOFOLLOW to (200 to fixture("article-nofollow.html")),
                PrLinkState.LINK_MISSING to (200 to fixture("article-no-link.html")),
                PrLinkState.PAGE_GONE to (404 to null),
                PrLinkState.BLOCKED_UNKNOWN to (403 to null),
            )
        val expected =
            listOf(
                Triple(PrLinkState.OK_FOLLOW, PrLinkState.OK_NOFOLLOW, true),
                Triple(PrLinkState.OK_FOLLOW, PrLinkState.PAGE_GONE, true),
                Triple(PrLinkState.OK_FOLLOW, PrLinkState.BLOCKED_UNKNOWN, false),
                Triple(PrLinkState.OK_FOLLOW, PrLinkState.OK_FOLLOW, false),
                Triple(PrLinkState.OK_NOFOLLOW, PrLinkState.LINK_MISSING, true),
                Triple(PrLinkState.OK_NOFOLLOW, PrLinkState.PAGE_GONE, true),
                Triple(PrLinkState.OK_NOFOLLOW, PrLinkState.BLOCKED_UNKNOWN, false),
                Triple(PrLinkState.OK_NOFOLLOW, PrLinkState.OK_FOLLOW, false),
                Triple(PrLinkState.NOT_CHECKED, PrLinkState.LINK_MISSING, false),
                Triple(PrLinkState.LINK_MISSING, PrLinkState.PAGE_GONE, false),
                Triple(PrLinkState.BLOCKED_UNKNOWN, PrLinkState.LINK_MISSING, false),
                Triple(PrLinkState.PAGE_GONE, PrLinkState.LINK_MISSING, false),
            )
        expected.forEachIndexed { i, (before, after, alert) ->
            val url = "https://n.example/$i"
            val id = placement(url = url, state = before)
            val (status, body) = html.getValue(after)
            page(url, status, body)
            val dto = service.check(id)
            dto.linkState shouldBe after
            dto.alert shouldBe alert
            dto.alertReason shouldBe (if (alert) "$before→$after" else null)
        }
    }

    @Test
    fun relativeLinksUseTheFinalUrl() {
        val id = placement(url = "https://news.example/a")
        page("https://news.example/a", 200, """<a href="/charter">x</a>""", finalUrl = "https://www.croatia-yachting.com/blog/a")

        service.check(id).linkState shouldBe PrLinkState.OK_FOLLOW
    }

    @Test
    fun xRobotsTagNofollowIsNofollow() {
        val id = placement(url = "https://news.example/a")
        page("https://news.example/a", 200, fixture("article-follow.html"), xRobotsTag = "nofollow")

        service.check(id).linkState shouldBe PrLinkState.OK_NOFOLLOW
    }

    @Test
    fun checkUnknownPlacementIs404() {
        shouldThrow<PrException> { service.check(999) }.shouldBeError(HttpStatus.NOT_FOUND, "PLACEMENT_NOT_FOUND")
        shouldThrow<PrException> { placementService.clearAlert(999) }.shouldBeError(HttpStatus.NOT_FOUND, "PLACEMENT_NOT_FOUND")
    }

    // ---- scheduled run ----

    @Test
    fun scheduledRunChecksRecentDailyAndOlderWeekly() {
        val hour = 3_600L
        val recentCheckedToday = placement(url = "https://a.example/1", publishedOn = today.minusDays(13), lastCheckedAt = now.minusSeconds(hour))
        val recentNever = placement(url = "https://b.example/2", publishedOn = today)
        val oldFresh = placement(url = "https://c.example/3", publishedOn = today.minusDays(30), lastCheckedAt = now.minus(6, ChronoUnit.DAYS))
        val oldStale = placement(url = "https://d.example/4", publishedOn = today.minusDays(30), lastCheckedAt = now.minus(8, ChronoUnit.DAYS))
        val oldNever = placement(url = "https://e.example/5", publishedOn = today.minusDays(400))
        // No publication date: its creation date decides (created now = recent).
        val undated = placement(url = "https://f.example/6", publishedOn = null, lastCheckedAt = now.minusSeconds(hour))
        val due = listOf(recentCheckedToday, recentNever, oldStale, oldNever, undated)
        placements.findAllById(due).forEach { page(it.articleUrl, 200, fixture("article-follow.html")) }

        service.checkDue() shouldBe 5

        placements.findAll().filter { it.lastCheckedAt == now }.map { it.id } shouldContainExactlyInAnyOrder due
        placements.findById(oldFresh).get().linkState shouldBe PrLinkState.NOT_CHECKED
        verify(fetcher, org.mockito.Mockito.never()).fetch("https://c.example/3")
    }

    @Test
    fun scheduledRunStopsAtMaxPerRun() {
        (1..3).forEach {
            placement(url = "https://h$it.example/a")
            page("https://h$it.example/a", 200, fixture("article-follow.html"))
        }
        service.maxPerRun = 2

        service.checkDue() shouldBe 2
        placements.findAll().count { it.lastCheckedAt == now } shouldBe 2
    }

    @Test
    fun scheduledRunPausesBetweenRequestsToTheSameHost() {
        listOf("https://same.example/1", "https://other.example/1", "https://SAME.example/2").forEach {
            placement(url = it)
            page(it, 200, fixture("article-follow.html"))
        }

        service.checkDue() shouldBe 3

        sleeps.size shouldBe 1
        (sleeps.single() in 1..2_000L) shouldBe true
    }

    @Test
    fun oneFailingPlacementDoesNotStopTheRun() {
        val broken = placement(url = "https://broken.example/a")
        val fine = placement(url = "https://fine.example/a")
        page("https://fine.example/a", 200, fixture("article-follow.html"))
        // No page for broken.example: the mock throws, as an unexpected bug would.

        service.checkDue() shouldBe 1

        placements.findById(fine).get().linkState shouldBe PrLinkState.OK_FOLLOW
        placements.findById(broken).get().linkState shouldBe PrLinkState.NOT_CHECKED
    }

    // ---- create / update ----

    @Test
    fun createNormalisesTheUrlAndRanksTheDomain() {
        val study =
            studies
                .save(
                    PrStudy().apply {
                        number = 1
                        siteId = "boat4you"
                        title = "T"
                    },
                ).id!!
        val outlet =
            outlets
                .save(
                    PrOutlet().apply {
                        name = "The Daily Example"
                        market = "GB"
                        tier = 1
                    },
                ).id!!
        `when`(opr.rank("example-news.co.uk")).thenReturn(BigDecimal("4.52"))

        val dto =
            placementService.create(
                PrPlacementRequest(
                    studyId = study,
                    outletId = outlet,
                    articleUrl = "  HTTPS://WWW.Example-News.co.uk/travel/a?id=7#comments ",
                    publishedOn = today,
                    targetUrl = " https://www.boat4you.com/press/x ",
                    anchorText = "  Boat4you study ",
                ),
            )

        dto.articleUrl shouldBe "https://www.example-news.co.uk/travel/a?id=7"
        dto.studyId shouldBe study
        dto.outletId shouldBe outlet
        dto.outletName shouldBe "The Daily Example"
        dto.targetUrl shouldBe "https://www.boat4you.com/press/x"
        dto.anchorText shouldBe "Boat4you study"
        dto.linkState shouldBe PrLinkState.NOT_CHECKED
        dto.domainRank shouldBe BigDecimal("4.52")
        dto.alert shouldBe false
        verifyNoInteractions(fetcher)
        placementService.list(null).map { it.id } shouldContainExactly listOf(dto.id)
        placementService.list(study).map { it.id } shouldContainExactly listOf(dto.id)
        placementService.list(study + 1) shouldBe emptyList()
    }

    @Test
    fun createWithoutRankStillWorks() {
        `when`(opr.rank(anyString())).thenReturn(null)

        placementService.create(PrPlacementRequest(articleUrl = "http://news.example/a")).domainRank shouldBe null
    }

    @Test
    fun duplicateArticleIsRejected() {
        placementService.create(PrPlacementRequest(articleUrl = "https://www.example-news.co.uk/travel/a"))

        listOf("https://www.example-news.co.uk/travel/a", " HTTPS://www.EXAMPLE-news.co.uk/travel/a#x").forEach {
            shouldThrow<PrException> { placementService.create(PrPlacementRequest(articleUrl = it)) }.shouldBeError(HttpStatus.CONFLICT, "PLACEMENT_EXISTS")
        }
        placements.count() shouldBe 1
    }

    @Test
    fun invalidArticleUrlIsRejected() {
        listOf(
            null,
            "",
            "  ",
            "ftp://news.example/a",
            "javascript:alert(1)",
            "news.example/a",
            "https:///a",
            "https://user:pw@news.example/a",
            "https://news.example/${"a".repeat(1000)}",
        ).forEach {
            shouldThrow<PrException> { placementService.create(PrPlacementRequest(articleUrl = it)) }.shouldBeError(HttpStatus.BAD_REQUEST, "ARTICLE_URL_INVALID")
        }
        placements.count() shouldBe 0
    }

    @Test
    fun unknownStudyOutletOrPitchIs404() {
        val url = "https://news.example/a"
        shouldThrow<PrException> { placementService.create(PrPlacementRequest(articleUrl = url, studyId = 42)) }.shouldBeError(HttpStatus.NOT_FOUND, "STUDY_NOT_FOUND")
        shouldThrow<PrException> { placementService.create(PrPlacementRequest(articleUrl = url, outletId = 42)) }.shouldBeError(HttpStatus.NOT_FOUND, "OUTLET_NOT_FOUND")
        shouldThrow<PrException> { placementService.create(PrPlacementRequest(articleUrl = url, pitchId = 42)) }.shouldBeError(HttpStatus.NOT_FOUND, "PITCH_NOT_FOUND")
        placements.count() shouldBe 0
    }

    @Test
    fun patchChangesOnlyTheFieldsSent() {
        val id = placementService.create(PrPlacementRequest(articleUrl = "https://news.example/a", anchorText = "a", targetUrl = "https://www.boat4you.com/")).id
        page("https://news.example/a", 200, fixture("article-no-link.html"))
        service.check(id)

        val dto = placementService.update(id, PrPlacementRequest(anchorText = "b", publishedOn = today))
        dto.anchorText shouldBe "b"
        dto.publishedOn shouldBe today
        dto.targetUrl shouldBe "https://www.boat4you.com/"
        dto.linkState shouldBe PrLinkState.LINK_MISSING

        placementService.update(id, PrPlacementRequest(targetUrl = " ")).targetUrl shouldBe null

        // A new article URL is a different page: the old result no longer applies.
        val moved = placementService.update(id, PrPlacementRequest(articleUrl = "https://news.example/b"))
        moved.articleUrl shouldBe "https://news.example/b"
        moved.linkState shouldBe PrLinkState.NOT_CHECKED
        moved.lastGoodState shouldBe null
        moved.lastHttpStatus shouldBe null
        moved.lastCheckedAt shouldBe null

        val other = placementService.create(PrPlacementRequest(articleUrl = "https://news.example/c")).id
        shouldThrow<PrException> { placementService.update(other, PrPlacementRequest(articleUrl = "https://news.example/b")) }
            .shouldBeError(HttpStatus.CONFLICT, "PLACEMENT_EXISTS")
        shouldThrow<PrException> { placementService.update(999, PrPlacementRequest(anchorText = "x")) }.shouldBeError(HttpStatus.NOT_FOUND, "PLACEMENT_NOT_FOUND")
    }
}
