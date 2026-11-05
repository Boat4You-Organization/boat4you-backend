package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppression
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.PrJpaTest.TestClock
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchUpdateRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrStudy
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.rules.PitchDecision
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrFooter
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Pitch drafts, the per-pitch send decision, follow-up drafts and manual status changes against the real V9_121 schema. Nothing is sent. */
class PrPitchDraftTest : PrJpaTest() {
    // PrProperties comes from the app's own binding (defaults: privacy URL https://www.boat4you.com/press/privacy).

    @Autowired private lateinit var service: PrPitchService

    @Autowired private lateinit var contactService: PrContactService

    @Autowired private lateinit var clock: TestClock

    @Autowired private lateinit var outlets: PrOutletRepository

    @Autowired private lateinit var contacts: PrContactRepository

    @Autowired private lateinit var studies: PrStudyRepository

    @Autowired private lateinit var pitches: PrPitchRepository

    @Autowired private lateinit var suppressions: EmailSuppressionRepository

    private val start = Instant.parse("2026-11-05T10:00:00Z")

    @BeforeEach
    fun resetClock() {
        clock.now = start
    }

    private fun outlet(
        name: String = "The Daily Example",
        market: String = "GB",
    ) = outlets.findFirstByNameIgnoreCaseAndMarket(name, market) ?: outlets.save(
        PrOutlet().apply {
            this.name = name
            this.market = market
            tier = 1
        },
    )

    private fun contact(
        email: String = "jane@example.co.uk",
        market: String? = "GB",
        outlet: PrOutlet? = outlet(),
        name: String? = "Jane Testwriter",
        status: PrContactStatus = PrContactStatus.ACTIVE,
    ) = contacts.save(
        PrContact().apply {
            outletId = outlet?.id
            this.name = name
            this.email = email
            this.market = market
            emailSourceUrl = "https://example.co.uk/authors/jane"
            emailFoundOn = LocalDate.of(2026, 11, 4)
            this.status = status
        },
    )

    private fun study(number: Int = 1) =
        studies.save(
            PrStudy().apply {
                this.number = number
                siteId = "catamaran-croatia"
                title = "Charter prices in Croatia rose 12% this summer"
            },
        )

    private fun draft(
        contactId: Long?,
        studyId: Long? = null,
        template: PrPitchTemplate? = PrPitchTemplate.DATA_PITCH,
        subject: String? = "Charter prices in Croatia rose 12%",
        body: String? = "Hi Jane,\n\nour bookings data shows ...\n\nMario",
    ) = PrPitchDraftRequest(studyId = studyId, contactId = contactId, template = template, subject = subject, body = body)

    /** Sending is Task 6: a test marks a pitch SENT straight in the table. */
    private fun markSent(
        pitchId: Long,
        at: Instant,
    ) = jdbc.update("UPDATE pr_pitch SET status = 'SENT', sent_at = ? WHERE id = ?", Timestamp.from(at), pitchId)

    private fun fails(block: () -> Unit): PrException = shouldThrow<PrException> { block() }

    private fun PrException.shouldBe(
        status: HttpStatus,
        code: String,
    ) {
        this.status shouldBe status
        this.code shouldBe code
    }

    @Test
    fun draftCarriesDecision() {
        val de = contact(email = "anna@zeitung.example", market = "DE", outlet = outlet("Die Beispielzeitung", "DE"))
        val gb = contact()

        val blocked = service.createDraft(draft(de.id))
        val allowed = service.createDraft(draft(gb.id))

        blocked.decision shouldBe "MARKET_DE_NO_COLD_EMAIL"
        blocked.status shouldBe PrPitchStatus.DRAFT
        allowed.decision shouldBe "ALLOWED"
        service.decision(blocked.id) shouldBe PitchDecision.Blocked("MARKET_DE_NO_COLD_EMAIL")
        service.decision(allowed.id) shouldBe PitchDecision.Allowed
        allowed.contactEmail shouldBe "jane@example.co.uk"
        allowed.contactName shouldBe "Jane Testwriter"
        allowed.outletName shouldBe "The Daily Example"
        allowed.footer shouldBe PrFooter.text("https://www.boat4you.com/press/privacy")
    }

    @Test
    fun marketFallsBackToOutlet() {
        val noMarket = contact(email = "anna@zeitung.example", market = null, outlet = outlet("Die Beispielzeitung", "DE"))
        val noMarketNoOutlet = contact(email = "free@lance.example", market = null, outlet = null)

        service.createDraft(draft(noMarket.id)).decision shouldBe "MARKET_DE_NO_COLD_EMAIL"
        service.createDraft(draft(noMarketNoOutlet.id)).decision shouldBe "MARKET_UNKNOWN"
    }

    @Test
    fun optOutBlocksEveryContactWithSameEmail() {
        val atGazette = contact(email = "ed@paper.example", outlet = outlet("Gazette"))
        val atTribune = contact(email = "ed@paper.example", outlet = outlet("Tribune"))
        val pitch = service.createDraft(draft(atTribune.id))
        pitch.decision shouldBe "ALLOWED"

        contactService.optOut(atGazette.id!!)

        service.decision(pitch.id) shouldBe PitchDecision.Blocked("SUPPRESSED")
    }

    @Test
    fun suppressionIsMatchedIgnoringCase() {
        val jane = contact()
        suppressions.save(
            EmailSuppression().apply {
                email = "JANE@Example.co.uk"
                reason = "MANUAL"
            },
        )

        service.createDraft(draft(jane.id)).decision shouldBe "SUPPRESSED"
    }

    @Test
    fun draftValidation() {
        val jane = contact()

        fails { service.createDraft(draft(jane.id, subject = "Unlike Trip Advisor reviews")) }.shouldBe(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        fails { service.createDraft(draft(jane.id, body = "Data, not tripadvisor stars")) }.shouldBe(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        fails { service.createDraft(draft(jane.id, subject = "  ")) }.shouldBe(HttpStatus.BAD_REQUEST, "SUBJECT_REQUIRED")
        fails { service.createDraft(draft(jane.id, subject = "x".repeat(301))) }.shouldBe(HttpStatus.BAD_REQUEST, "SUBJECT_TOO_LONG")
        fails { service.createDraft(draft(jane.id, body = " \n ")) }.shouldBe(HttpStatus.BAD_REQUEST, "BODY_REQUIRED")
        fails { service.createDraft(draft(jane.id, template = null)) }.shouldBe(HttpStatus.BAD_REQUEST, "TEMPLATE_REQUIRED")
        fails { service.createDraft(draft(jane.id, template = PrPitchTemplate.FOLLOW_UP)) }.shouldBe(HttpStatus.BAD_REQUEST, "TEMPLATE_NOT_ALLOWED")
        fails { service.createDraft(draft(null)) }.shouldBe(HttpStatus.BAD_REQUEST, "CONTACT_REQUIRED")
        fails { service.createDraft(draft(999)) }.shouldBe(HttpStatus.NOT_FOUND, "CONTACT_NOT_FOUND")
        fails { service.createDraft(draft(jane.id, studyId = 999)) }.shouldBe(HttpStatus.NOT_FOUND, "STUDY_NOT_FOUND")
        pitches.count() shouldBe 0

        val ok = service.createDraft(draft(jane.id, subject = "  ${"x".repeat(300)}  "))
        ok.subject shouldBe "x".repeat(300)
    }

    @Test
    fun secondMainPitchToSameContactForSameStudyIs409() {
        val jane = contact()
        val tom = contact(email = "tom@example.co.uk")
        val study = study()
        service.createDraft(draft(jane.id, studyId = study.id))

        fails { service.createDraft(draft(jane.id, studyId = study.id, template = PrPitchTemplate.NEWSJACK)) }
            .shouldBe(HttpStatus.CONFLICT, "PITCH_EXISTS")

        // Another contact, another study, or no study at all: allowed.
        service.createDraft(draft(tom.id, studyId = study.id))
        service.createDraft(draft(jane.id, studyId = study(2).id))
        service.createDraft(draft(jane.id))
        service.createDraft(draft(jane.id))
        pitches.count() shouldBe 5
    }

    @Test
    fun followUpOnlyOnce() {
        val study = study()
        val jane = contact()
        val main = service.createDraft(draft(jane.id, studyId = study.id, subject = "Charter prices in Croatia rose 12%"))

        fails { service.createFollowUpDraft(main.id, "Just checking in") }.shouldBe(HttpStatus.CONFLICT, "FOLLOWUP_NOT_ALLOWED")
        markSent(main.id, start)
        fails { service.createFollowUpDraft(main.id, "Unlike TripAdvisor ...") }.shouldBe(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        fails { service.createFollowUpDraft(main.id, "  ") }.shouldBe(HttpStatus.BAD_REQUEST, "BODY_REQUIRED")
        fails { service.createFollowUpDraft(999, "Just checking in") }.shouldBe(HttpStatus.NOT_FOUND, "PITCH_NOT_FOUND")

        clock.now = start + Duration.ofDays(1)
        val followUp = service.createFollowUpDraft(main.id, "Just checking in")

        followUp.parentPitchId shouldBe main.id
        followUp.template shouldBe PrPitchTemplate.FOLLOW_UP
        followUp.subject shouldBe "Re: Charter prices in Croatia rose 12%"
        followUp.contactId shouldBe jane.id
        followUp.studyId shouldBe study.id
        followUp.status shouldBe PrPitchStatus.DRAFT
        followUp.decision shouldBe "FOLLOWUP_TOO_EARLY"
        clock.now = start + Duration.ofDays(5)
        service.decision(followUp.id) shouldBe PitchDecision.Allowed

        fails { service.createFollowUpDraft(main.id, "Once more") }.shouldBe(HttpStatus.CONFLICT, "FOLLOWUP_EXISTS")
        markSent(followUp.id, start)
        fails { service.createFollowUpDraft(followUp.id, "A follow-up of a follow-up") }.shouldBe(HttpStatus.CONFLICT, "FOLLOWUP_NOT_ALLOWED")
    }

    @Test
    fun followUpSubjectIsCutTo300() {
        val main = service.createDraft(draft(contact().id, subject = "y".repeat(300)))
        markSent(main.id, start)

        service.createFollowUpDraft(main.id, "Just checking in").subject shouldBe "Re: " + "y".repeat(296)
    }

    @Test
    fun editAfterSentIs409() {
        val pitch = service.createDraft(draft(contact().id))

        val edited = service.updateDraft(pitch.id, PrPitchUpdateRequest(subject = " New subject ", body = "New body"))
        edited.subject shouldBe "New subject"
        edited.body shouldBe "New body"
        service.updateDraft(pitch.id, PrPitchUpdateRequest(body = "Only the body")).subject shouldBe "New subject"
        fails { service.updateDraft(pitch.id, PrPitchUpdateRequest(body = "Better than TRIP-ADVISOR")) }.shouldBe(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        fails { service.updateDraft(pitch.id, PrPitchUpdateRequest(subject = "")) }.shouldBe(HttpStatus.BAD_REQUEST, "SUBJECT_REQUIRED")
        fails { service.updateDraft(999, PrPitchUpdateRequest(body = "x")) }.shouldBe(HttpStatus.NOT_FOUND, "PITCH_NOT_FOUND")

        markSent(pitch.id, start)

        fails { service.updateDraft(pitch.id, PrPitchUpdateRequest(body = "Too late")) }.shouldBe(HttpStatus.CONFLICT, "PITCH_NOT_DRAFT")
        // The edit is one guarded UPDATE: a pitch that left DRAFT after it was loaded is not overwritten either.
        pitches.updateDraft(pitch.id, "Late subject", "Late body") shouldBe 0
        pitches.findById(pitch.id).get().body shouldBe "Only the body"
        pitches.findById(pitch.id).get().status shouldBe PrPitchStatus.SENT
    }

    @Test
    fun statusIsSetByHandOnlyAfterSending() {
        val pitch = service.createDraft(draft(contact().id))

        fails { service.setStatus(pitch.id, PrPitchStatus.REPLIED, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        fails { service.setStatus(pitch.id, PrPitchStatus.SENT, null) }.shouldBe(HttpStatus.BAD_REQUEST, "STATUS_NOT_ALLOWED")
        // DRAFT is a target only from FAILED (the retry path, PrSendTest).
        fails { service.setStatus(pitch.id, PrPitchStatus.DRAFT, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        fails { service.setStatus(999, PrPitchStatus.REPLIED, null) }.shouldBe(HttpStatus.NOT_FOUND, "PITCH_NOT_FOUND")
        markSent(pitch.id, start)

        val replied = service.setStatus(pitch.id, PrPitchStatus.REPLIED, "  Wants the regional breakdown ")
        replied.status shouldBe PrPitchStatus.REPLIED
        replied.replyNote shouldBe "Wants the regional breakdown"
        val published = service.setStatus(pitch.id, PrPitchStatus.PUBLISHED, null)
        published.status shouldBe PrPitchStatus.PUBLISHED
        published.replyNote shouldBe "Wants the regional breakdown"
        service.setStatus(pitch.id, PrPitchStatus.DECLINED, "Changed their mind").replyNote shouldBe "Changed their mind"
    }

    @Test
    fun bouncedPitchSuppressesTheAddress() {
        val jane = contact()
        val sameAddressElsewhere = contact(outlet = outlet("Tribune"))
        val pitch = service.createDraft(draft(jane.id))
        markSent(pitch.id, start)

        service.setStatus(pitch.id, PrPitchStatus.BOUNCED, null).status shouldBe PrPitchStatus.BOUNCED

        contacts.findById(jane.id!!).get().status shouldBe PrContactStatus.OPTED_OUT
        contacts.findById(sameAddressElsewhere.id!!).get().status shouldBe PrContactStatus.OPTED_OUT
        suppressions.findByEmailIgnoreCase("jane@example.co.uk")!!.reason shouldBe "BOUNCE"
        fails { service.setStatus(pitch.id, PrPitchStatus.REPLIED, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
    }

    @Test
    fun listIsNewestFirstAndFilters() {
        val study = study()
        val jane = contact()
        val tom = contact(email = "tom@example.co.uk", name = null)
        val a = service.createDraft(draft(jane.id, studyId = study.id))
        val b = service.createDraft(draft(tom.id, studyId = study.id))
        val c = service.createDraft(draft(jane.id))
        markSent(a.id, start)

        service.list(null, null).map { it.id } shouldBe listOf(c.id, b.id, a.id)
        service.list(study.id, null).map { it.id } shouldBe listOf(b.id, a.id)
        service.list(study.id, PrPitchStatus.SENT).map { it.id } shouldBe listOf(a.id)
        service.list(null, PrPitchStatus.DRAFT).map { it.id } shouldBe listOf(c.id, b.id)
        val tomsPitch = service.list(study.id, PrPitchStatus.DRAFT).single()
        tomsPitch.contactEmail shouldBe "tom@example.co.uk"
        tomsPitch.contactName shouldBe null
        tomsPitch.outletName shouldBe "The Daily Example"
        tomsPitch.decision shouldBe "ALLOWED"
    }

    @Test
    fun footerText() {
        PrFooter.text("https://www.boat4you.com/press/privacy") shouldBe
            "Boat4you Group, Vrboran 37, 21000 Split, Croatia · How we handle your data: https://www.boat4you.com/press/privacy\n" +
            "Not the right person, or don't want these? Just reply and I won't email you again."
        PrFooter.appendTo("Hi Jane,\n\nMario\n\n  ", "https://x.example/p") shouldBe
            "Hi Jane,\n\nMario\n\n" + PrFooter.text("https://x.example/p")
    }
}
