package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.offermail.OfferMailReader
import hr.workspace.boat4you.domains.calls.offermail.OfferMailService
import hr.workspace.boat4you.domains.calls.offermail.OfferMailboxes
import hr.workspace.boat4you.domains.calls.service.AdminCallService
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallEligibility
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.calls.web.AdminCallController
import hr.workspace.boat4you.domains.catalouge.capacity.PartnerTextSanitizer
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The Call centar admin API over the real tables (design §2.5); the role check is AdminCallSecurityTests. */
class AdminCallControllerTest : CallsJpaTest() {
    private val now: Instant = Instant.parse("2026-11-03T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val audio = CallAudioStore(Files.createTempDirectory("admin-audio").toString())
    private val service by lazy {
        AdminCallService(calls, inquiries, jdbc, CallPlanner(calls, inquiries, settings, jdbc, clock), CallEligibility(calls, jdbc), settings, audio, clock)
    }

    @Autowired private lateinit var agencies: AgencyRepository

    @Autowired private lateinit var manufacturers: ManufacturerRepository

    @Autowired private lateinit var models: ModelRepository

    private val offerMail by lazy {
        val sanitizer = PartnerTextSanitizer(agencies).apply { refreshExecutor = java.util.concurrent.Executor { it.run() } }
        sanitizer.loadAgencyNames()
        OfferMailService(OfferMailReader(OfferMailboxes("", "")), inquiries, sanitizer, manufacturers, models, jdbc)
    }
    private val mvc by lazy {
        MockMvcBuilders.standaloneSetup(AdminCallController(service, offerMail)).setControllerAdvice(ApiErrorHandler()).setMessageConverters(*CallsTestJson.converters()).build()
    }

    private fun json(b: String): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit =
        {
            contentType = MediaType.APPLICATION_JSON
            content = b
        }

    @Test
    fun `the list filters by status and site and searches the client`() {
        val it1 =
            inquiry {
                site = "italy"
                name = "Giulia"
            }
        val gr =
            inquiry {
                site = "greece"
                name = "Nikos"
            }
        call(it1, status = CallStatus.DONE) { outcome = CallOutcome.CONFIRMED }
        call(it1, status = CallStatus.SCHEDULED)
        call(gr, status = CallStatus.DONE)
        mvc.get("/admin/calls?status=DONE&site=italy").andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].site") { value("italy") }
            jsonPath("$.content[0].clientName") { value("Giulia") }
            jsonPath("$.page.totalElements") { value(1) }
        }
        mvc.get("/admin/calls?q=nikos").andExpect { jsonPath("$.content.length()") { value(1) } }
    }

    @Test
    fun `the detail carries transcript, data, the inquiry and its other calls`() {
        val i =
            inquiry {
                site = "italy"
                name = "Giulia"
                offerNote = "Lagoon 46"
            }
        val c =
            call(i, status = CallStatus.DONE) {
                transcript = listOf(mapOf("role" to "agent", "message" to "Dobar dan"))
                data = mapOf("people" to 8)
                summary = "Sažetak"
            }
        call(i, kind = CallKind.FOLLOW_UP)
        mvc.get("/admin/calls/${c.id}").andExpect {
            status { isOk() }
            jsonPath("$.call.summary") { value("Sažetak") }
            jsonPath("$.transcript[0].message") { value("Dobar dan") }
            jsonPath("$.data.people") { value(8) }
            jsonPath("$.inquiry.offerNote") { value("Lagoon 46") }
            jsonPath("$.calls.length()") { value(2) }
            jsonPath("$.hasAudio") { value(false) }
        }
        mvc.get("/admin/calls/999999").andExpect { status { isNotFound() } }
    }

    @Test
    fun `offer sent takes a note of at most 300 characters and plans the follow-up`() {
        val i = inquiry()
        mvc.post("/admin/calls/inquiries/${i.id}/offer-sent", dsl = json("""{"note":"${"x".repeat(301)}"}""")).andExpect { status { isBadRequest() } }
        mvc.post("/admin/calls/inquiries/${i.id}/offer-sent", dsl = json("""{"note":"Lagoon 46, 11.900 €"}""")).andExpect { status { isNoContent() } }
        calls.findByInquiryIdOrderByCreatedAtDesc(i.id!!).single().kind shouldBe CallKind.FOLLOW_UP
    }

    @Test
    fun `offer sent stores the e-mail text without charter company names, builders stay`() {
        manufacturers.saveAndFlush(Manufacturer().apply { name = "Lagoon" })
        val i = inquiry()
        jdbc.update("UPDATE inquiry SET created_at = '2026-11-02 10:00:00' WHERE id = ?", i.id)
        val body = """{"note":null,"emailText":"Dear Anna,\nLagoon 42 from the Sunsail base in Split, 7.850 EUR.","emailSentAt":"2026-11-03T08:00:00Z"}"""
        mvc.post("/admin/calls/inquiries/${i.id}/offer-sent", dsl = json(body)).andExpect { status { isNoContent() } }
        val saved = inquiries.findById(i.id!!).get()
        saved.offerEmailText shouldBe "Dear Anna,\nLagoon 42 from the [charter company] base in Split, 7.850 EUR."
        saved.offerEmailSentAt shouldBe Instant.parse("2026-11-03T08:00:00Z")

        // A later "Ponuda poslana" without an e-mail must not leave the old text for the new call.
        mvc.post("/admin/calls/inquiries/${i.id}/offer-sent", dsl = json("""{"note":"Bali 4.6"}""")).andExpect { status { isNoContent() } }
        inquiries.findById(i.id!!).get().offerEmailText shouldBe null
    }

    @Test
    fun `offer e-mail preview says when no mailboxes are configured`() {
        val i = inquiry()
        mvc.get("/admin/calls/inquiries/${i.id}/offer-email").andExpect {
            status { isOk() }
            jsonPath("$.configured") { value(false) }
            jsonPath("$.found") { value(false) }
        }
        mvc.get("/admin/calls/inquiries/999999/offer-email").andExpect { status { isNotFound() } }
    }

    @Test
    fun `call now is refused with the reason when the rules forbid it`() {
        settings.write(mapOf("calls.enabled" to "true"))
        val noConsent = inquiry(consent = false)
        mvc.post("/admin/calls/inquiries/${noConsent.id}/call-now", dsl = json("{}")).andExpect {
            status { isConflict() }
            jsonPath("$.skipReason") { value("NO_CONSENT") }
        }
        val ok = inquiry()
        mvc.post("/admin/calls/inquiries/${ok.id}/call-now", dsl = json("{}")).andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("MANUAL") }
        }
    }

    @Test
    fun `human takeover and do not call cancel the scheduled calls`() {
        val i = inquiry()
        call(i)
        mvc.post("/admin/calls/inquiries/${i.id}/human-takeover").andExpect { status { isNoContent() } }
        calls.findByInquiryIdOrderByCreatedAtDesc(i.id!!).single().status shouldBe CallStatus.CANCELLED
        mvc.post("/admin/calls/inquiries/${i.id}/do-not-call").andExpect { status { isNoContent() } }
        inquiries.findById(i.id!!).get().doNotCall shouldBe true
    }

    @Test
    fun `resolve closes Mario's callback task only`() {
        val i = inquiry()
        val task = call(i, kind = CallKind.HUMAN_CALLBACK)
        val ai = call(i)
        mvc.post("/admin/calls/${task.id}/resolve").andExpect { status { isNoContent() } }
        calls.findById(task.id!!).get().status shouldBe CallStatus.DONE
        mvc.post("/admin/calls/${ai.id}/resolve").andExpect { status { isConflict() } }
    }

    @Test
    fun `settings are validated on write and the transfer number is masked on read`() {
        mvc.put("/admin/calls/settings", dsl = json("""{"calls.window.start":"25:00"}""")).andExpect { status { isBadRequest() } }
        mvc.put("/admin/calls/settings", dsl = json("""{"calls.transfer.number":"+385981234567","calls.limit.daily":"50"}""")).andExpect { status { isNoContent() } }
        mvc.get("/admin/calls/settings").andExpect {
            status { isOk() }
            jsonPath("$['calls.transfer.number']") { value("**********567") }
            jsonPath("$['calls.limit.daily']") { value("50") }
            jsonPath("$['calls.enabled']") { value("false") }
        }
    }

    @Test
    fun `the recording streams as audio mpeg and a call without one is 404`() {
        val c = call(inquiry(), status = CallStatus.DONE)
        mvc.get("/admin/calls/${c.id}/audio").andExpect { status { isNotFound() } }
        c.audioPath = audio.save("conv-a", byteArrayOf(7, 7), now)
        calls.save(c)
        mvc.get("/admin/calls/${c.id}/audio").andExpect {
            status { isOk() }
            content { contentType("audio/mpeg") }
            content { bytes(byteArrayOf(7, 7)) }
        }
    }

    @Test
    fun `the inquiries page lists consent, site and the last call`() {
        val i =
            inquiry {
                site = "cy"
                consentTextVersion = "calls-consent-v1"
            }
        call(i, status = CallStatus.DONE) { outcome = CallOutcome.CONFIRMED }
        mvc.get("/admin/calls/inquiries?site=cy").andExpect {
            status { isOk() }
            jsonPath("$.content[0].inquiry.site") { value("cy") }
            jsonPath("$.content[0].inquiry.contactConsent") { value(true) }
            jsonPath("$.content[0].lastOutcome") { value("CONFIRMED") }
        }
    }

    @Test
    fun `call now is refused while calls are switched off`() {
        settings.write(mapOf("calls.enabled" to "false"))
        val i = inquiry()
        mvc.post("/admin/calls/inquiries/${i.id}/call-now", dsl = json("{}")).andExpect {
            status { isConflict() }
            jsonPath("$.skipReason") { value("CALLS_OFF") }
        }
    }

    @Test
    fun `call now outside the client's window answers with the client's next slot`() {
        settings.write(mapOf("calls.enabled" to "true"))
        val newYork = inquiry(phone = "+12125550100") // 09:00 UTC = 04:00 New York -> 09:30 New York = 14:30 UTC
        mvc.post("/admin/calls/inquiries/${newYork.id}/call-now", dsl = json("{}")).andExpect {
            status { isOk() }
            jsonPath("$.scheduledAt") { value("2026-11-03T14:30:00Z") }
        }
    }
}
