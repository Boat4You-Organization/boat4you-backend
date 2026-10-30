package hr.workspace.boat4you.domains.contactcenter.controllers

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import hr.workspace.boat4you.domains.contactcenter.service.BeatCommand
import hr.workspace.boat4you.domains.contactcenter.service.BeatService
import hr.workspace.boat4you.domains.contactcenter.service.CcSettings
import hr.workspace.boat4you.domains.contactcenter.service.DeliveredMessage
import hr.workspace.boat4you.domains.contactcenter.service.HandoffTokenStore
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveDelivery
import hr.workspace.boat4you.domains.contactcenter.service.TrackerInputValidator
import hr.workspace.boat4you.domains.contactcenter.service.TrackerSurgeGuard
import hr.workspace.boat4you.domains.contactcenter.service.TrackerUrlPolicy
import hr.workspace.boat4you.domains.contactcenter.service.VisitCommand
import hr.workspace.boat4you.domains.contactcenter.service.VisitService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Instant

class PublicTrackerControllerTest {
    private val urls = TrackerUrlPolicy(BrandRegistry(), "")
    private val validator = TrackerInputValidator(urls, jacksonObjectMapper())
    private val visits = Mockito.mock(VisitService::class.java)
    private val beats = Mockito.mock(BeatService::class.java)
    private val handoffs = HandoffTokenStore()

    // P1a's CcSettings reads the DB; a mock keeps this a pure web test. Mockito's default for a Boolean is false, so stub true.
    private val settings = Mockito.mock(CcSettings::class.java).also { Mockito.`when`(it.trackerEnabled).thenReturn(true) }
    private val clock = CcTestClock()
    private val guard = TrackerSurgeGuard(2).also { it.clock = clock } // a site budget of 2 visits a minute in this test; fixed clock so the minute window never rolls mid-test
    private val controller = PublicTrackerController(validator, visits, beats, handoffs, settings, jacksonObjectMapper(), guard)
    private val mvc = MockMvcBuilders.standaloneSetup(controller).build()
    private val key = "a0000000-0000-4000-8000-000000000001"
    private val visitKey = "b0000000-0000-4000-8000-000000000002"
    private val b4y = "https://www.boat4you.com"

    private fun post(
        path: String,
        body: String,
        origin: String? = b4y,
    ) = mvc.post("/public/cc/v1/$path") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        origin?.let { header("Origin", it) }
        header("X-Real-IP", "203.0.113.7")
        header("X-Forwarded-For", "1.1.1.1")
        header("User-Agent", CHROME_UA)
    }

    private fun ResultActionsDsl.rejectedWith(
        code: Int,
        error: String,
    ): ResultActionsDsl =
        andExpect {
            status { isEqualTo(code) }
            jsonPath("$.error") { value(error) }
        }

    private val visitBody = """{"siteId":"boat4you","visitorKey":"$key","landingUrl":"$b4y/"}"""
    private val beatBody = """{"siteId":"boat4you","visitorKey":"$key","visitId":"$visitKey","visible":true}"""

    @Test
    fun `tracker switched off answers 204 without touching services`() {
        Mockito.`when`(settings.trackerEnabled).thenReturn(false)
        post("visit", visitBody).andExpect { status { isNoContent() } }
        post("beat", "{}").andExpect { status { isNoContent() } }
        Mockito.verifyNoInteractions(visits, beats)
    }

    @Test
    fun `body over 32 KB is 413 and bad JSON is 400`() {
        post("visit", """{"siteId":"boat4you","x":"${"a".repeat(40_000)}"}""").andExpect { status { isPayloadTooLarge() } }
        post("visit", "{not json").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a chunked body over 32 KB, with no Content-Length to go by, is 413 too`() {
        val chunked =
            object : MockHttpServletRequest("POST", "/public/cc/v1/visit") {
                override fun getContentLengthLong(): Long = -1
            }.apply { setContent(ByteArray(PublicTrackerController.MAX_BODY_BYTES + 1) { 'a'.code.toByte() }) }
        controller.visit(chunked).statusCode.value() shouldBe 413
        Mockito.verifyNoInteractions(visits)
    }

    @Test
    fun `a JSON null or an empty body is 400, not a crash`() {
        post("visit", "null").rejectedWith(400, "json")
        post("beat", "").rejectedWith(400, "json")
        Mockito.verifyNoInteractions(visits, beats)
    }

    @Test
    fun `origin of another site or no origin is 403`() {
        post("visit", visitBody, "https://catamaran-charter-greece.com").rejectedWith(403, "origin")
        post("visit", visitBody, null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `visit returns opaque keys, no cookie, no cache, the X-Real-IP address`() { // BACKEND-24, BACKEND-28
        Mockito.`when`(visits.startOrResume(any())).thenReturn(VisitService.Started(visitKey, key, false))
        post("visit", visitBody).andExpect {
            status { isOk() }
            jsonPath("$.visitId") { value(visitKey) }
            jsonPath("$.visitorId") { value(key) }
            jsonPath("$.beatSeconds") { value(15) }
            jsonPath("$.minBeatGapSeconds") { value(5) }
            header { string("Cache-Control", "no-store") }
            header { doesNotExist("Set-Cookie") }
        }
        val captor: ArgumentCaptor<VisitCommand> = ArgumentCaptor.forClass(VisitCommand::class.java)
        Mockito.verify(visits).startOrResume(capture(captor))
        captor.value.ip shouldBe "203.0.113.7"
    }

    @Test
    fun `every answer is no-store and cookie-free, rejections included`() {
        listOf(
            post("visit", "{not json"),
            post("visit", visitBody, null),
            post("beat", """{"siteId":"boat4you","visitorKey":"$key","visitId":"42"}"""),
        ).forEach {
            it.andExpect {
                header { string("Cache-Control", "no-store") }
                header { doesNotExist("Set-Cookie") }
            }
        }
        Mockito.`when`(settings.trackerEnabled).thenReturn(false)
        post("beat", beatBody).andExpect {
            status { isNoContent() }
            header { string("Cache-Control", "no-store") }
        }
    }

    @Test
    fun `a site over its visit budget answers 429 and never reaches the database`() { // design 2.6 "po stranici"
        Mockito.`when`(visits.startOrResume(any())).thenReturn(VisitService.Started(visitKey, key, false))
        repeat(2) { post("visit", visitBody).andExpect { status { isOk() } } }
        post("visit", visitBody).rejectedWith(429, "site_busy")
        Mockito.verify(visits, Mockito.times(2)).startOrResume(any())
    }

    @Test
    fun `the site budget is spent only by validated visits`() { // allowVisit runs after the validator, so garbage never reaches the guard's maps
        Mockito.`when`(visits.startOrResume(any())).thenReturn(VisitService.Started(visitKey, key, false))
        repeat(5) { post("visit", visitBody, "https://catamaran-charter-greece.com").andExpect { status { isForbidden() } } }
        repeat(5) { post("visit", """{"siteId":"nope","visitorKey":"$key"}""").andExpect { status { isBadRequest() } } }
        repeat(2) { post("visit", visitBody).andExpect { status { isOk() } } }
        post("visit", visitBody).andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `a pool timeout is 503, not 500`() {
        Mockito.`when`(visits.startOrResume(any())).thenThrow(CannotCreateTransactionException("pool timeout"))
        post("visit", visitBody).rejectedWith(503, "unavailable")
    }

    @Test
    fun `a database error from the service is 503, not 500`() { // the DataAccessException branch of guarded(); the pool timeout above is a TransactionException
        Mockito.`when`(visits.startOrResume(any())).thenThrow(DataAccessResourceFailureException("db down"))
        post("visit", visitBody).rejectedWith(503, "unavailable")
    }

    @Test
    fun `a failed or refused call gives its database permit back`() { // the bulkhead holds 8: a leaked permit would turn a 9th call into "busy"
        Mockito.`when`(beats.beat(any())).thenThrow(CannotCreateTransactionException("pool timeout"))
        repeat(TrackerSurgeGuard.MAX_CONCURRENT + 2) {
            post("beat", beatBody).rejectedWith(503, "unavailable")
        }
        Mockito.`when`(visits.startOrResume(any())).thenReturn(VisitService.Started(visitKey, key, false))
        repeat(2) { post("visit", visitBody).andExpect { status { isOk() } } }
        repeat(TrackerSurgeGuard.MAX_CONCURRENT + 2) {
            post("visit", visitBody).rejectedWith(429, "site_busy")
        }
    }

    @Test
    fun `no database permit left is 503 busy`() {
        repeat(TrackerSurgeGuard.MAX_CONCURRENT) { guard.tryEnter() shouldBe true }
        post("beat", beatBody).rejectedWith(503, "busy")
        Mockito.verifyNoInteractions(beats)
    }

    @Test
    fun `numeric visitId is 400`() { // BACKEND-29
        post("beat", """{"siteId":"boat4you","visitorKey":"$key","visitId":"42"}""").rejectedWith(400, "visit_id")
    }

    @Test
    fun `beat maps outcomes to 404, 204 and 200`() {
        Mockito.`when`(beats.beat(any())).thenReturn(BeatService.BeatOutcome.UnknownVisit)
        post("beat", beatBody).rejectedWith(404, "unknown_visit")
        Mockito.`when`(beats.beat(any())).thenReturn(BeatService.BeatOutcome.Quiet)
        post("beat", beatBody).andExpect { status { isNoContent() } }
        Mockito
            .`when`(beats.beat(any()))
            .thenReturn(BeatService.BeatOutcome.Delivered(ProactiveDelivery("tok", listOf(DeliveredMessage(5, "Bok", Instant.parse("2026-10-26T10:00:00Z"))))))
        post("beat", beatBody).andExpect {
            status { isOk() }
            jsonPath("$.chatToken") { value("tok") }
            jsonPath("$.proactiveMessages[0].text") { value("Bok") }
            jsonPath("$.proactiveMessages[0].createdAt") { value("2026-10-26T10:00:00Z") }
        }
    }

    @Test
    fun `null items in pageViews and events are dropped by the validator, never a 500`() { // Task 6 ruling: a JSON null element reaches the validator
        Mockito.`when`(beats.beat(any())).thenReturn(BeatService.BeatOutcome.Quiet)
        post("beat", """{"siteId":"boat4you","visitorKey":"$key","visitId":"$visitKey","pageViews":[null],"events":[null]}""")
            .andExpect { status { isNoContent() } }
        val captor: ArgumentCaptor<BeatCommand> = ArgumentCaptor.forClass(BeatCommand::class.java)
        Mockito.verify(beats).beat(capture(captor))
        captor.value.dropped shouldBe 2
    }

    @Test
    fun `handoff issues a token for a live human visit, 204 for a bot`() {
        val e = liveEntry(visitKey = visitKey, visitorKey = key, siteId = "catamaran-greece")
        Mockito.`when`(visits.resolveForBeat(visitKey, key, "catamaran-greece")).thenReturn(e)
        val body = """{"siteId":"catamaran-greece","visitorKey":"$key","visitId":"$visitKey","toSiteId":"boat4you"}"""
        post("handoff", body, "https://catamaran-charter-greece.com").andExpect {
            status { isOk() }
            jsonPath("$.expiresInSeconds") { value(120) }
            header { string("Cache-Control", "no-store") }
        }
        e.bot = true
        post("handoff", body, "https://catamaran-charter-greece.com").andExpect { status { isNoContent() } }
    }

    @Test
    fun `handoff of an unknown visit is 404 and the token it issues is redeemable once on the destination`() {
        val body = """{"siteId":"catamaran-greece","visitorKey":"$key","visitId":"$visitKey","toSiteId":"boat4you"}"""
        post("handoff", body, "https://catamaran-charter-greece.com").rejectedWith(404, "unknown_visit")
        Mockito
            .`when`(visits.resolveForBeat(visitKey, key, "catamaran-greece"))
            .thenReturn(liveEntry(visitKey = visitKey, visitorKey = key, siteId = "catamaran-greece"))
        val token = jacksonObjectMapper().readTree(post("handoff", body, "https://catamaran-charter-greece.com").andReturn().response.contentAsString)["token"].asText()
        token.length shouldBe 43
        handoffs.consume(token, "boat4you")?.visitorKey shouldBe key
        handoffs.consume(token, "boat4you") shouldBe null
    }

    @Test
    fun `a full handoff store answers 204`() {
        Mockito
            .`when`(visits.resolveForBeat(visitKey, key, "catamaran-greece"))
            .thenReturn(liveEntry(visitKey = visitKey, visitorKey = key, siteId = "catamaran-greece"))
        repeat(HandoffTokenStore.MAX_TOKENS) { handoffs.issue(key, "catamaran-greece", "boat4you") }
        post("handoff", """{"siteId":"catamaran-greece","visitorKey":"$key","visitId":"$visitKey","toSiteId":"boat4you"}""", "https://catamaran-charter-greece.com")
            .andExpect { status { isNoContent() } }
    }

    // The usual Kotlin null-safety shims: Mockito's matchers return null, and the mocked methods take non-null parameters.
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = Mockito.any<T>() as T

    // ArgumentCaptor.capture() would hand the non-null parameter a null that Kotlin rejects at the call site: register the matcher, then cast.
    // The captor must be declared with a non-null type argument, or the platform type brings the null check back.
    @Suppress("UNCHECKED_CAST")
    private fun <T> capture(captor: ArgumentCaptor<T>): T {
        captor.capture()
        return null as T
    }
}
