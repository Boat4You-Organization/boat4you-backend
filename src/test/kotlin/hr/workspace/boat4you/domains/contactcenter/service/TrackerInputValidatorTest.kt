package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.controllers.BeatRequest
import hr.workspace.boat4you.domains.contactcenter.controllers.EventDto
import hr.workspace.boat4you.domains.contactcenter.controllers.HandoffRequest
import hr.workspace.boat4you.domains.contactcenter.controllers.PageViewDto
import hr.workspace.boat4you.domains.contactcenter.controllers.ViewportDto
import hr.workspace.boat4you.domains.contactcenter.controllers.VisitRequest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The strict input rules of the three tracker calls: identity, site host, Origin, item caps and clipped text. */
class TrackerInputValidatorTest {
    private val v = TrackerInputValidator(TrackerUrlPolicy(BrandRegistry(), ""), jacksonObjectMapper()).also { it.clock = CcTestClock() }
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private val key = "a0000000-0000-4000-8000-000000000001"
    private val visitKey = "b0000000-0000-4000-8000-000000000002"
    private val b4y = "https://www.boat4you.com"

    private fun beat(
        pvs: List<PageViewDto> = emptyList(),
        evs: List<EventDto> = emptyList(),
        visitId: String = visitKey,
    ) = v.beat(BeatRequest("boat4you", key, visitId, true, pvs, evs), b4y)

    @Test
    fun `visit is cleaned and identity is checked`() {
        val ok =
            v.visit(
                VisitRequest(
                    siteId = "boat4you",
                    visitorKey = key.uppercase(),
                    landingUrl = "$b4y/boat/x-1?utm_source=g&gclid=1",
                    locale = "pt-BR",
                    viewport = ViewportDto(390, 844),
                ),
                b4y,
                "203.0.113.7",
                CHROME_UA,
            ) as Checked.Ok
        ok.value.visitorKey shouldBe key
        ok.value.landing!!.path shouldBe "/boat/x-1"
        ok.value.utm.source shouldBe "g"
        ok.value.gclid shouldBe "1"
        ok.value.landingParamNames shouldBe setOf("utm_source", "gclid")
        ok.value.locale shouldBe "pt-BR"
        v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, locale = "x".repeat(20)), b4y, "1.1.1.1", CHROME_UA).let { (it as Checked.Ok).value.locale } shouldBe null
        v.visit(VisitRequest(siteId = "nope", visitorKey = key), b4y, "1.1.1.1", null) shouldBe Checked.Rejected(400, "site")
        v.visit(VisitRequest(siteId = "boat4you", visitorKey = key), "https://catamaran-charter-greece.com", "1.1.1.1", null) shouldBe Checked.Rejected(403, "origin")
        v.visit(VisitRequest(siteId = "boat4you", visitorKey = "123"), b4y, "1.1.1.1", null) shouldBe Checked.Rejected(400, "visitor_key")
        v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, landingUrl = "https://europe-yachts.com/"), b4y, "1.1.1.1", null) shouldBe Checked.Rejected(403, "host")
        (v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, landingUrl = "$b4y/trip/tok"), b4y, "1.1.1.1", null) as Checked.Ok).value.landing shouldBe null
        (v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, landingUrl = "$b4y/boat/x-1?cc_src=catamaran-greece"), b4y, "1.1.1.1", null) as Checked.Ok)
            .value
            .let {
                it.ccSrc shouldBe "catamaran-greece"
                it.landing!!.path shouldBe "/boat/x-1"
            }
        (v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, landingUrl = "$b4y/boat/x-1?cc_src=evil"), b4y, "1.1.1.1", null) as Checked.Ok).value.ccSrc shouldBe null
    }

    @Test
    fun `beat with 21 page views is rejected and bad items are dropped`() {
        beat(List(21) { PageViewDto("pv$it-abcdef", "$b4y/") }) shouldBe Checked.Rejected(400, "too_many_items")
        beat(evs = List(51) { EventDto("ev%02d-abcdef".format(it), "CLICK") }) shouldBe Checked.Rejected(400, "too_many_items")
        val tenKeys = (1..10).associate { "k$it" to "v" }
        val clipped =
            beat(
                listOf(PageViewDto("pv9-abcdef", "$b4y/", "t".repeat(301))),
                listOf(
                    EventDto("ev9-abcdef", "CLICK", null, "l".repeat(201), "g".repeat(301), tenKeys + ("k11" to "v")),
                    EventDto("ev8-abcdef", "CLICK", payload = tenKeys),
                ),
            ) as Checked.Ok
        clipped.value.pageViews.single().title shouldBe "t".repeat(300)
        clipped.value.events[0].let {
            it.label shouldBe "l".repeat(200)
            it.target shouldBe "g".repeat(300)
            it.payloadJson shouldBe null // 11 keys: whole payload dropped
        }
        clipped.value.events[1].payloadJson shouldBe jacksonObjectMapper().writeValueAsString(tenKeys) // 10 keys: kept
        beat(visitId = "42") shouldBe Checked.Rejected(400, "visit_id") // BACKEND-29
        beat(listOf(PageViewDto("pv1-abcdef", "https://europe-yachts.com/x"))) shouldBe Checked.Rejected(403, "host")
        val ok =
            beat(
                listOf(
                    PageViewDto("pv1-abcdef", "$b4y/hr/boat/x-1", "Lagoon\u0000 42", now.toEpochMilli()),
                    PageViewDto("pv2-abcdef", "$b4y/review/tok"),
                    PageViewDto("bad id!", "$b4y/"),
                ),
                listOf(
                    EventDto("ev1-abcdef", "CLICK", "pv1-abcdef", "Book online", "/boat/x-1", mapOf("n" to 1, "nested" to mapOf("a" to 1)), now.minusSeconds(7200).toEpochMilli()),
                    EventDto("ev2-abcdef", "HACK"),
                    EventDto("ev3-abcdef", "SEARCH", payload = mapOf("location" to "Split", "startDate" to "2027-07-17", "persons" to 6, "countryCode" to "HR")),
                    EventDto("ev4-abcdef", "CONSENT", payload = mapOf("analytics" to false)),
                ),
            ) as Checked.Ok
        ok.value.pageViews.single().let {
            it.pageType shouldBe "BOAT"
            it.boatSlug shouldBe "x-1"
            it.title shouldBe "Lagoon 42"
            it.viewedAt shouldBe now
        }
        ok.value.dropped shouldBe 3
        ok.value.events.map { it.type } shouldBe listOf("CLICK", "SEARCH", "CONSENT")
        ok.value.events[0].let {
            it.payloadJson shouldBe null
            it.createdAt shouldBe now
        }
        ok.value.events[1].search shouldBe SiteSearchCriteria("HR", "Split", null, LocalDate.parse("2027-07-17"), null, 6, null, null, null, null, null, null, null)
        ok.value.events[2].consentWithdrawn shouldBe true
    }

    @Test
    fun `handoff needs another known site`() {
        v.handoff(HandoffRequest("boat4you", key, visitKey, "boat4you"), b4y) shouldBe Checked.Rejected(400, "to_site")
        (v.handoff(HandoffRequest("catamaran-greece", key, visitKey, "boat4you"), "https://catamaran-charter-greece.com") as Checked.Ok).value.toSiteId shouldBe "boat4you"
    }

    @Test
    fun `visit text is bounded, the body wins over the landing URL and odd viewports are dropped`() {
        val long = "u".repeat(250)
        val ok =
            v.visit(
                VisitRequest(
                    siteId = "boat4you",
                    visitorKey = key,
                    landingUrl = "$b4y/?utm_source=fromurl&utm_medium=cpc&gclid=$long",
                    utmSource = " body\u0007 ",
                    utmCampaign = long,
                    handoffToken = "t".repeat(300),
                    viewport = ViewportDto(-1, 10_001),
                ),
                b4y,
                "1.1.1.1",
                "x".repeat(401), // the 400 character clip keeps the user-agent regexes cheap (Task 4)
            ) as Checked.Ok
        ok.value.userAgent shouldBe "x".repeat(400)
        ok.value.utm shouldBe Utm(source = "body", medium = "cpc", campaign = "u".repeat(200))
        ok.value.gclid shouldBe "u".repeat(200)
        ok.value.handoffToken shouldBe "t".repeat(100)
        ok.value.viewportW shouldBe null
        ok.value.viewportH shouldBe null
        val edge =
            v.visit(
                VisitRequest(siteId = "boat4you", visitorKey = key, viewport = ViewportDto(0, 10_000), webdriver = true, staff = true),
                b4y,
                "1.1.1.1",
                null,
            ) as Checked.Ok
        edge.value.viewportW shouldBe 0
        edge.value.viewportH shouldBe 10_000
        edge.value.webdriver shouldBe true
        edge.value.staff shouldBe true
        edge.value.landing shouldBe null
        edge.value.landingParamNames shouldBe emptySet()
        (v.visit(VisitRequest(siteId = "boat4you", visitorKey = key, landingUrl = "$b4y/%"), b4y, "1.1.1.1", null)) shouldBe Checked.Rejected(400, "landing_url")
    }

    @Test
    fun `checks run in a fixed order`() {
        val bad = BeatRequest("boat4you", "x", "y", true)
        v.beat(bad.copy(siteId = null), b4y) shouldBe Checked.Rejected(400, "site")
        v.beat(bad.copy(siteId = "nope"), "https://evil.example") shouldBe Checked.Rejected(400, "site")
        v.beat(bad, "https://evil.example") shouldBe Checked.Rejected(403, "origin")
        v.beat(bad, null) shouldBe Checked.Rejected(403, "origin")
        v.beat(bad, b4y) shouldBe Checked.Rejected(400, "visitor_key")
        v.beat(bad.copy(visitorKey = key), b4y) shouldBe Checked.Rejected(400, "visit_id")
        v.beat(bad.copy(visitorKey = key, visitId = visitKey.replace("-4", "-1")), b4y) shouldBe Checked.Rejected(400, "visit_id") // not a version 4 UUID
        (v.beat(bad.copy(visitorKey = key.uppercase(), visitId = visitKey.uppercase(), visible = null), b4y) as Checked.Ok).value.let {
            it.visitorKey shouldBe key
            it.visitKey shouldBe visitKey
            it.visible shouldBe true
        }
        (v.beat(bad.copy(visitorKey = key, visitId = visitKey, visible = false), b4y) as Checked.Ok).value.visible shouldBe false
        // too many items is answered before any item is looked at
        v.beat(BeatRequest("boat4you", key, visitKey, true, List(21) { PageViewDto("pv$it-abcdef", "https://europe-yachts.com/") }), b4y) shouldBe
            Checked.Rejected(400, "too_many_items")
        v.handoff(HandoffRequest("boat4you", key, "42", "nope"), b4y) shouldBe Checked.Rejected(400, "visit_id")
        v.handoff(HandoffRequest("boat4you", key, visitKey, null), b4y) shouldBe Checked.Rejected(400, "to_site")
        v.handoff(HandoffRequest("boat4you", key, visitKey, "nope"), b4y) shouldBe Checked.Rejected(400, "to_site")
    }

    @Test
    fun `client times are kept only inside the window`() {
        fun viewedAt(at: Long?) = (beat(listOf(PageViewDto("pv1-abcdef", "$b4y/", at = at))) as Checked.Ok).value.pageViews.single().viewedAt
        viewedAt(null) shouldBe now
        viewedAt(now.minusSeconds(59 * 60).toEpochMilli()) shouldBe now.minusSeconds(59 * 60)
        viewedAt(now.minusSeconds(61 * 60).toEpochMilli()) shouldBe now
        viewedAt(now.plusSeconds(4 * 60).toEpochMilli()) shouldBe now.plusSeconds(4 * 60)
        viewedAt(now.plusSeconds(6 * 60).toEpochMilli()) shouldBe now
        viewedAt(Long.MAX_VALUE) shouldBe now
        viewedAt(Long.MIN_VALUE) shouldBe now
    }

    @Test
    fun `payload is flat, bounded and keeps its order`() {
        v.payloadOf(null) shouldBe null
        v.payloadOf(emptyMap()) shouldBe null
        v.payloadOf(mapOf("a" to null)) shouldBe null // nothing left once null values are skipped
        v.payloadOf(mapOf("bad key" to "v")) shouldBe null
        v.payloadOf(mapOf("k".repeat(41) to "v")) shouldBe null
        v.payloadOf(mapOf("a" to listOf(1))) shouldBe null
        v.payloadOf(mapOf("a" to mapOf("b" to 1))) shouldBe null
        v.payloadOf(mapOf("z" to 1, "a" to null, "b" to 1.5, "c" to true, "d" to "x".repeat(250), "e" to " \u0000 ")) shouldBe
            mapOf("z" to "1", "b" to "1.5", "c" to "true", "d" to "x".repeat(200))
    }

    @Test
    fun `a search keeps only the criteria that pass their range`() {
        fun search(payload: Map<String, Any?>?) = (beat(evs = listOf(EventDto("ev1-abcdef", "SEARCH", payload = payload))) as Checked.Ok).value.events.single().search
        // a payload holds at most 10 keys, so the 13 criteria arrive in more than one event
        search(
            mapOf(
                "countryCode" to "hr",
                "location" to "<b>",
                "did" to -4,
                "startDate" to "2025-10-25",
                "endDate" to "2029-10-27",
                "persons" to 61,
                "cabins" to 0,
                "minPrice" to -1,
                "maxPrice" to 10_000_001,
                "vesselType" to "Sailing_Yacht",
            ),
        ) shouldBe SiteSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null)
        search(mapOf("equipment" to "AC,Dinghy", "resultCount" to 100_001, "totalAvailable" to 7)) shouldBe
            SiteSearchCriteria(null, null, null, null, null, null, null, null, null, null, "AC,Dinghy", null, 7)
        search(
            mapOf(
                "countryCode" to "HR",
                "location" to "Rijeka (Šoltanski kanal)",
                "did" to 7,
                "startDate" to "2025-10-26",
                "endDate" to "2029-10-26",
                "persons" to 60,
                "cabins" to 1,
                "minPrice" to 0,
                "maxPrice" to 10_000_000,
                "vesselType" to "SAILING_YACHT",
            ),
        ) shouldBe
            SiteSearchCriteria(
                "HR",
                "Rijeka (Šoltanski kanal)",
                7L,
                LocalDate.parse("2025-10-26"),
                LocalDate.parse("2029-10-26"),
                60,
                1,
                0,
                10_000_000,
                "SAILING_YACHT",
                null,
                null,
                null,
            )
        search(mapOf("resultCount" to 0, "totalAvailable" to 100_000)) shouldBe
            SiteSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, 0, 100_000)
        search((1..11).associate { "k$it" to "v" }) shouldBe null // 11 keys: the whole payload, and with it the search, is dropped
        search(mapOf("startDate" to "2027-02-30", "endDate" to "x")) shouldBe SiteSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null)
        search(null) shouldBe null // the event is kept, there is nothing to store as a search
        search(mapOf("nested" to mapOf("a" to 1))) shouldBe null
        (beat(evs = listOf(EventDto("ev1-abcdef", "CLICK", payload = mapOf("persons" to 6)))) as Checked.Ok).value.events.single().search shouldBe null
    }

    @Test
    fun `an event keeps a page view reference only when it is a client id and consent is withdrawn only by analytics false`() {
        val events =
            (
                beat(
                    evs =
                        listOf(
                            EventDto("ev1-abcdef", "CLICK", pvId = "pv1-abcdef"),
                            EventDto("ev2-abcdef", "CLICK", pvId = "no good!"),
                            EventDto("ev3-abcdef", "CONSENT", payload = mapOf("analytics" to true)),
                            EventDto("ev4-abcdef", "CONSENT", payload = mapOf("analytics" to "false")),
                            EventDto("ev5-abcdef", "CONSENT"),
                            EventDto("ev6-abcdef", "CLICK", payload = mapOf("analytics" to false)),
                            EventDto(null, "CLICK"),
                            EventDto("ev8-abcdef", null),
                        ),
                ) as Checked.Ok
            ).value
        events.events.map { it.clientId } shouldBe listOf("ev1-abcdef", "ev2-abcdef", "ev3-abcdef", "ev4-abcdef", "ev5-abcdef", "ev6-abcdef")
        events.events.map { it.pvClientId } shouldBe listOf("pv1-abcdef", null, null, null, null, null)
        events.events.map { it.consentWithdrawn } shouldBe listOf(false, false, false, true, false, false)
        events.dropped shouldBe 2
    }

    @Test
    fun `a null element of the page view or event list is dropped and counted, never an error`() {
        // Jackson puts a null into a Kotlin List<..> (StrictNullChecks is off in Boot's KotlinModule), so the body is read the way the controller reads it
        val body =
            """{"siteId":"boat4you","visitorKey":"$key","visitId":"$visitKey","pageViews":[null,{"id":"pv1-abcdef","url":"$b4y/"}],"events":[null]}"""
        (v.beat(jacksonObjectMapper().readValue<BeatRequest>(body), b4y) as Checked.Ok).value.let {
            it.pageViews.size shouldBe 1
            it.dropped shouldBe 2
        }
    }
}
