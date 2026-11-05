package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.PrJpaTest.TestClock
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrFooter
import hr.workspace.boat4you.domains.pr.service.PrMailSender
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Sending a pitch from press@ (Task 6) against the real V9_121 schema. SMTP is a Mockito JavaMailSender: nothing leaves
 * the machine. The captured MimeMessage goes through saveChanges() as JavaMailSenderImpl does before transmitting it.
 *
 * The shared PR context binds application.yml, where the kill switch is off. Sending therefore goes through [service]:
 * the same PrPitchService class with the same repositories, transaction manager and clock, but mail switched on
 * ([mailOn]) and the SMTP mock behind its PrMailSender. Drafts and status changes go through the Spring bean
 * ([pitchService]). No second Spring context is built for this.
 */
class PrSendTest : PrJpaTest() {
    @Autowired private lateinit var pitchService: PrPitchService

    @Autowired private lateinit var props: PrProperties

    @Autowired private lateinit var clock: TestClock

    @Autowired private lateinit var outlets: PrOutletRepository

    @Autowired private lateinit var contacts: PrContactRepository

    @Autowired private lateinit var studies: PrStudyRepository

    @Autowired private lateinit var pitches: PrPitchRepository

    @Autowired private lateinit var suppressions: EmailSuppressionRepository

    @Autowired private lateinit var contactService: PrContactService

    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val smtp: JavaMailSender = mock(JavaMailSender::class.java)

    /** The bound mail settings, switched on and configured with test values (the host does not resolve). */
    private val mailOn by lazy {
        props.mail.copy(
            enabled = true,
            host = "smtp.test.invalid",
            username = "press@boat4you.com",
            password = "pw-never-shown",
        )
    }

    private val service: PrPitchService by lazy { serviceWith(mailOn) }

    private val mailSender: PrMailSender by lazy { PrMailSender(props.copy(mail = mailOn)) { smtp } }

    private val start = Instant.parse("2026-11-05T10:00:00Z")

    private val privacyUrl = "https://www.boat4you.com/press/privacy"

    @BeforeEach
    fun resetState() {
        clock.now = start
        reset(smtp)
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
        market: String = "GB",
        status: PrContactStatus = PrContactStatus.ACTIVE,
    ) = contacts.save(
        PrContact().apply {
            outletId = outlet(market = market).id
            name = "Jane Testwriter"
            this.email = email
            this.market = market
            emailSourceUrl = "https://example.co.uk/authors/jane"
            emailFoundOn = LocalDate.of(2026, 11, 4)
            this.status = status
        },
    )

    private val body = "Hi Jane,\n\nour bookings data shows charter prices in Split rose 12% – details below.\n\nMario\n\n"

    private fun draft(
        contactId: Long,
        template: PrPitchTemplate = PrPitchTemplate.DATA_PITCH,
        subject: String = "Charter prices in Croatia rose 12% – Split & Hvar",
    ) = pitchService.createDraft(PrPitchDraftRequest(studyId = null, contactId = contactId, template = template, subject = subject, body = body))

    /** The one message handed to SMTP, finalised as JavaMailSenderImpl does (saveChanges) before it goes out. */
    private fun sent(): MimeMessage {
        val captor = ArgumentCaptor.forClass(MimeMessage::class.java)
        verify(smtp, times(1)).send(captor.capture())
        return captor.value.also { it.saveChanges() }
    }

    private fun raw(m: MimeMessage) = ByteArrayOutputStream().also { m.writeTo(it) }.toString(Charsets.UTF_8)

    private fun status(pitchId: Long) = jdbc.queryForObject("SELECT status FROM pr_pitch WHERE id = ?", String::class.java, pitchId)

    private fun fails(block: () -> Unit): PrException = shouldThrow<PrException> { block() }

    private fun PrException.shouldBe(
        status: HttpStatus,
        code: String,
    ) {
        this.status shouldBe status
        this.code shouldBe code
    }

    /** The same service with other mail settings (kill switch, missing config); same repositories and SMTP mock. */
    private fun serviceWith(mail: PrProperties.Mail): PrPitchService {
        val p = props.copy(mail = mail)
        return PrPitchService(pitches, contacts, outlets, studies, suppressions, contactService, p, PrMailSender(p) { smtp }, txManager, clock)
    }

    private fun withTrigger(
        sql: String,
        block: () -> Unit,
    ) {
        jdbc.execute(sql)
        try {
            block()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS pr_send_test ON pr_pitch; DROP FUNCTION IF EXISTS pr_send_test()")
        }
    }

    @Test
    fun sendAppendsFooterAndUsesPressFrom() {
        val jane = contact()
        val pitch = draft(jane.id!!)

        val result = service.send(pitch.id)

        val m = sent()
        val from = m.from.single() as InternetAddress
        from.address shouldBe "press@boat4you.com"
        from.personal shouldBe "Mario Kuzmanić | Boat4you Group"
        (InternetAddress.parse(m.getHeader("From", null)).single()).personal shouldBe "Mario Kuzmanić | Boat4you Group"
        m.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address } shouldContainExactly listOf("jane@example.co.uk")
        m.getRecipients(Message.RecipientType.BCC).map { (it as InternetAddress).address } shouldContainExactly listOf("press@boat4you.com")
        m.getRecipients(Message.RecipientType.CC) shouldBe null
        m.subject shouldBe "Charter prices in Croatia rose 12% – Split & Hvar"
        m.contentType.lowercase() shouldBe "text/plain; charset=utf-8"
        m.content shouldBe PrFooter.appendTo(body, privacyUrl)
        (m.content as String).endsWith(PrFooter.text(privacyUrl)) shouldBe true
        m.getHeader("In-Reply-To") shouldBe null
        m.getHeader("References") shouldBe null
        val wire = raw(m)
        wire.lowercase() shouldNotContain "text/html"
        wire.lowercase() shouldNotContain "multipart"
        wire.lowercase() shouldNotContain "<img"

        result.status shouldBe PrPitchStatus.SENT
        result.sentAt shouldBe start
        result.error shouldBe null
        status(pitch.id) shouldBe "SENT"
        contacts.findById(jane.id!!).get().lastContactedAt shouldBe start
        contacts.findById(jane.id!!).get().status shouldBe PrContactStatus.ACTIVE
    }

    @Test
    fun sentMessageIdMatchesHeader() {
        val pitch = draft(contact().id!!)

        val result = service.send(pitch.id)

        val m = sent()
        result.messageId!! shouldMatch Regex("<[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@boat4you\\.com>")
        m.messageID shouldBe result.messageId
        m.saveChanges()
        m.messageID shouldBe result.messageId
        pitches.findById(pitch.id).get().messageId shouldBe result.messageId
    }

    @Test
    fun secondSendOfSamePitchIsRejected() {
        val pitch = draft(contact().id!!)
        var inFlight: PrException? = null
        doAnswer {
            // While SMTP runs: no transaction is open, SENDING is already committed, and a second click is refused.
            TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
            status(pitch.id) shouldBe "SENDING"
            inFlight = fails { service.send(pitch.id) }
            null
        }.`when`(smtp).send(any(MimeMessage::class.java))

        service.send(pitch.id).status shouldBe PrPitchStatus.SENT

        inFlight!!.shouldBe(HttpStatus.CONFLICT, "ALREADY_SENDING_OR_SENT")
        fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "ALREADY_SENDING_OR_SENT")
        verify(smtp, times(1)).send(any(MimeMessage::class.java))
    }

    @Test
    fun blockedDecisionNeverReachesSmtp() {
        val anna = contact(email = "anna@zeitung.example", market = "DE")
        val pitch = draft(anna.id!!)

        fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "MARKET_DE_NO_COLD_EMAIL")
        fails { service.send(999) }.shouldBe(HttpStatus.NOT_FOUND, "PITCH_NOT_FOUND")

        verify(smtp, never()).send(any(MimeMessage::class.java))
        status(pitch.id) shouldBe "DRAFT"
        contacts.findById(anna.id!!).get().lastContactedAt shouldBe null
    }

    @Test
    fun suppressedBetweenCheckAndSendingIsNeverSent() {
        val jane = contact()
        val pitch = draft(jane.id!!)
        // An opt-out that lands right as the pitch is frozen (after the first check): the re-check on the frozen pitch stops it.
        withTrigger(
            """
            CREATE FUNCTION pr_send_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF NEW.status = 'SENDING' THEN
                INSERT INTO email_suppression (email, reason) SELECT email, 'MANUAL' FROM pr_contact WHERE id = NEW.contact_id;
              END IF;
              RETURN NEW;
            END ${'$'}${'$'};
            CREATE TRIGGER pr_send_test AFTER UPDATE ON pr_pitch FOR EACH ROW EXECUTE FUNCTION pr_send_test();
            """.trimIndent(),
        ) {
            fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "SUPPRESSED")
        }

        verify(smtp, never()).send(any(MimeMessage::class.java))
        val saved = pitches.findById(pitch.id).get()
        saved.status shouldBe PrPitchStatus.FAILED
        saved.error!! shouldContain "SUPPRESSED"
        saved.messageId shouldBe null
        contacts.findById(jane.id!!).get().lastContactedAt shouldBe null
    }

    @Test
    fun disabledKillSwitchIs409() {
        val pitch = draft(contact().id!!)
        val off = serviceWith(mailOn.copy(enabled = false))

        fails { off.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "MAIL_DISABLED")
        fails { off.send(999) }.shouldBe(HttpStatus.CONFLICT, "MAIL_DISABLED")

        verify(smtp, never()).send(any(MimeMessage::class.java))
        status(pitch.id) shouldBe "DRAFT"
        PrProperties().mail.enabled shouldBe false
        props.mail.enabled shouldBe false
        fails { pitchService.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "MAIL_DISABLED")
    }

    @Test
    fun sendRefusesWhenMailNotConfigured() {
        val pitch = draft(contact().id!!)

        listOf(
            mailOn.copy(host = " "),
            mailOn.copy(username = ""),
            mailOn.copy(password = ""),
        ).forEach { mail ->
            fails { serviceWith(mail).send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "MAIL_NOT_CONFIGURED")
        }
        // The kill switch is checked first.
        fails { serviceWith(mailOn.copy(enabled = false, host = "")).send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "MAIL_DISABLED")

        verify(smtp, never()).send(any(MimeMessage::class.java))
        status(pitch.id) shouldBe "DRAFT"
    }

    @Test
    fun smtpFailureMarksFailedWithError() {
        val jane = contact()
        val pitch = draft(jane.id!!)
        doThrow(MailAuthenticationException("535 5.7.8 Authentication failed for pw-never-shown"))
            .`when`(smtp)
            .send(any(MimeMessage::class.java))

        val failed = service.send(pitch.id)

        failed.status shouldBe PrPitchStatus.FAILED
        failed.sentAt shouldBe null
        failed.messageId shouldBe null
        failed.error!! shouldContain "535 5.7.8 Authentication failed"
        failed.error!! shouldNotContain "pw-never-shown"
        failed.error!! shouldNotContain "bookings data"
        status(pitch.id) shouldBe "FAILED"
        contacts.findById(jane.id!!).get().lastContactedAt shouldBe null
        // Not re-sendable until put back to DRAFT by hand.
        fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "ALREADY_SENDING_OR_SENT")

        reset(smtp)
        pitchService.setStatus(pitch.id, PrPitchStatus.DRAFT, null).error shouldBe null
        service.send(pitch.id).status shouldBe PrPitchStatus.SENT
        verify(smtp, times(1)).send(any(MimeMessage::class.java))
    }

    @Test
    fun connectionFailureMarksFailed() {
        val pitch = draft(contact().id!!)
        doThrow(MailSendException("Mail server connection failed", MessagingException("Connection timed out"), mapOf<Any, Exception>("m" to MessagingException("x"))))
            .`when`(smtp)
            .send(any(MimeMessage::class.java))

        val failed = service.send(pitch.id)

        failed.status shouldBe PrPitchStatus.FAILED
        failed.error!! shouldContain "Mail server connection failed"
    }

    @Test
    fun closeFailureAfterAcceptedMessageCountsAsSent() {
        val pitch = draft(contact().id!!)
        // JavaMailSenderImpl throws this (no failed messages) only when the server took the message and QUIT failed.
        doThrow(MailSendException("Failed to close server connection after message sending", MessagingException("421 closing")))
            .`when`(smtp)
            .send(any(MimeMessage::class.java))

        val result = service.send(pitch.id)

        result.status shouldBe PrPitchStatus.SENT
        result.messageId!! shouldStartWith "<"
        fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "ALREADY_SENDING_OR_SENT")
    }

    @Test
    fun failedPitchCanGoBackToDraftOnlyFromFailed() {
        val draft = draft(contact().id!!)
        fails { pitchService.setStatus(draft.id, PrPitchStatus.DRAFT, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")

        val sent = service.send(draft.id)
        fails { pitchService.setStatus(sent.id, PrPitchStatus.DRAFT, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")

        val stuck = draft(contact(email = "tom@example.co.uk").id!!)
        pitches.markSending(stuck.id) shouldBe 1
        fails { pitchService.setStatus(stuck.id, PrPitchStatus.DRAFT, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        fails { pitchService.setStatus(stuck.id, PrPitchStatus.REPLIED, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        status(stuck.id) shouldBe "SENDING"

        val failed = draft(contact(email = "ann@example.co.uk").id!!)
        jdbc.update("UPDATE pr_pitch SET status = 'FAILED', error = 'boom' WHERE id = ?", failed.id)
        fails { pitchService.setStatus(failed.id, PrPitchStatus.REPLIED, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        fails { pitchService.setStatus(failed.id, PrPitchStatus.SENT, null) }.shouldBe(HttpStatus.BAD_REQUEST, "STATUS_NOT_ALLOWED")
        fails { pitchService.setStatus(failed.id, PrPitchStatus.SENDING, null) }.shouldBe(HttpStatus.BAD_REQUEST, "STATUS_NOT_ALLOWED")
        val back = pitchService.setStatus(failed.id, PrPitchStatus.DRAFT, null)
        back.status shouldBe PrPitchStatus.DRAFT
        back.error shouldBe null
    }

    @Test
    fun followUpSetsInReplyTo() {
        val jane = contact()
        val main = service.send(draft(jane.id!!, subject = "Charter prices in Croatia rose 12%").id)
        reset(smtp)
        clock.now = start + Duration.ofDays(5)
        val followUp = pitchService.createFollowUpDraft(main.id, "Hi Jane,\n\njust checking you saw this.\n\nMario")

        val result = service.send(followUp.id)

        val m = sent()
        m.getHeader("In-Reply-To", null) shouldBe main.messageId
        m.getHeader("References", null) shouldBe main.messageId
        m.subject shouldBe "Re: Charter prices in Croatia rose 12%"
        m.content shouldBe PrFooter.appendTo("Hi Jane,\n\njust checking you saw this.\n\nMario", privacyUrl)
        result.status shouldBe PrPitchStatus.SENT
        result.messageId shouldBe m.messageID
        (result.messageId == main.messageId) shouldBe false
        result.sentAt shouldBe start + Duration.ofDays(5)
        contacts.findById(jane.id!!).get().lastContactedAt shouldBe start + Duration.ofDays(5)
    }

    @Test
    fun followUpIsNotSentAfterTheMainPitchWasAnswered() {
        val jane = contact()
        val main = service.send(draft(jane.id!!).id)
        clock.now = start + Duration.ofDays(6)
        val followUp = pitchService.createFollowUpDraft(main.id, "Hi Jane,\n\njust checking you saw this.\n\nMario")
        followUp.decision shouldBe "ALLOWED"

        pitchService.setStatus(main.id, PrPitchStatus.REPLIED, "Wants the regional breakdown")

        fails { service.send(followUp.id) }.shouldBe(HttpStatus.CONFLICT, "FOLLOWUP_PARENT_ANSWERED")
        status(followUp.id) shouldBe "DRAFT"
        verify(smtp, times(1)).send(any(MimeMessage::class.java))
    }

    @Test
    fun doNotContactSavedDuringSendIsNotReverted() {
        val jane = contact()
        val za = contact(email = "thandi@news.example", market = "ZA")
        // Mario sets DO_NOT_CONTACT while the send is being recorded: the pitch row turns SENT in the same transaction.
        withTrigger(
            """
            CREATE FUNCTION pr_send_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF NEW.status = 'SENT' THEN UPDATE pr_contact SET status = 'DO_NOT_CONTACT' WHERE id = NEW.contact_id; END IF;
              RETURN NEW;
            END ${'$'}${'$'};
            CREATE TRIGGER pr_send_test AFTER UPDATE ON pr_pitch FOR EACH ROW EXECUTE FUNCTION pr_send_test();
            """.trimIndent(),
        ) {
            service.send(draft(jane.id!!).id).status shouldBe PrPitchStatus.SENT
            service.send(draft(za.id!!, PrPitchTemplate.CONSENT_REQUEST, "May I send you our charter data?").id).status shouldBe PrPitchStatus.SENT
        }

        contacts.findById(jane.id!!).get().let {
            it.status shouldBe PrContactStatus.DO_NOT_CONTACT
            it.lastContactedAt shouldBe start
        }
        // A consent request moves only ACTIVE to CONSENT_REQUESTED, as the database holds it at that moment.
        contacts.findById(za.id!!).get().status shouldBe PrContactStatus.DO_NOT_CONTACT
    }

    @Test
    fun consentRequestMovesContactToConsentRequested() {
        val za = contact(email = "thandi@news.example", market = "ZA")
        val consented = contact(email = "lee@paper.example", status = PrContactStatus.CONSENTED)

        service.send(draft(za.id!!, PrPitchTemplate.CONSENT_REQUEST, "May I send you our charter data?").id).status shouldBe PrPitchStatus.SENT
        service.send(draft(consented.id!!, PrPitchTemplate.CONSENT_REQUEST, "May I send you our charter data?").id).status shouldBe PrPitchStatus.SENT

        contacts.findById(za.id!!).get().status shouldBe PrContactStatus.CONSENT_REQUESTED
        contacts.findById(za.id!!).get().lastContactedAt shouldBe start
        // Consent already given is never taken back by a consent request.
        contacts.findById(consented.id!!).get().status shouldBe PrContactStatus.CONSENTED
        // POPIA: one approach only; a second consent request to the ZA contact is blocked.
        fails { service.send(draft(za.id!!, PrPitchTemplate.CONSENT_REQUEST, "Second ask").id) }.shouldBe(HttpStatus.CONFLICT, "ZA_CONSENT_REQUIRED")
    }

    @Test
    fun outcomeSaveFailureLeavesPitchSendingAndNeverResends() {
        val jane = contact()
        val pitch = draft(jane.id!!)

        withTrigger(
            """
            CREATE FUNCTION pr_send_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF NEW.status = 'SENT' THEN RAISE EXCEPTION 'database went away'; END IF;
              RETURN NEW;
            END ${'$'}${'$'};
            CREATE TRIGGER pr_send_test BEFORE UPDATE ON pr_pitch FOR EACH ROW EXECUTE FUNCTION pr_send_test();
            """.trimIndent(),
        ) {
            shouldThrowAny { service.send(pitch.id) }
        }

        status(pitch.id) shouldBe "SENDING"
        pitches.findById(pitch.id).get().messageId shouldBe null
        contacts.findById(jane.id!!).get().lastContactedAt shouldBe null
        fails { service.send(pitch.id) }.shouldBe(HttpStatus.CONFLICT, "ALREADY_SENDING_OR_SENT")
        fails { pitchService.setStatus(pitch.id, PrPitchStatus.DRAFT, null) }.shouldBe(HttpStatus.CONFLICT, "STATUS_TRANSITION_NOT_ALLOWED")
        verify(smtp, times(1)).send(any(MimeMessage::class.java))
    }

    @Test
    fun defaultSenderUsesSmtpsOrStarttlsWithTimeouts() {
        val smtps = PrMailSender.defaultSender(PrProperties.Mail(host = "mail.example.com", username = "u", password = "p")) as JavaMailSenderImpl
        smtps.host shouldBe "mail.example.com"
        smtps.port shouldBe 465
        smtps.username shouldBe "u"
        smtps.protocol shouldBe "smtp"
        smtps.javaMailProperties.getProperty("mail.smtp.auth") shouldBe "true"
        smtps.javaMailProperties.getProperty("mail.smtp.ssl.enable") shouldBe "true"
        smtps.javaMailProperties.getProperty("mail.smtp.starttls.enable") shouldBe "false"
        listOf("connectiontimeout", "timeout", "writetimeout").forEach {
            smtps.javaMailProperties.getProperty("mail.smtp.$it") shouldBe "15000"
        }

        val starttls = PrMailSender.defaultSender(PrProperties.Mail(host = "h", port = 587, ssl = false)) as JavaMailSenderImpl
        starttls.port shouldBe 587
        starttls.javaMailProperties.getProperty("mail.smtp.ssl.enable") shouldBe "false"
        starttls.javaMailProperties.getProperty("mail.smtp.starttls.enable") shouldBe "true"
        starttls.javaMailProperties.getProperty("mail.smtp.starttls.required") shouldBe "true"
    }

    @Test
    fun mailSenderBuildsPlainTextOnly() {
        val id = mailSender.send("x@example.com", "Ćao – subject", "Body ć", inReplyTo = null)

        val m = sent()
        m.messageID shouldBe id
        m.subject shouldBe "Ćao – subject"
        m.content shouldBe "Body ć"
        raw(m) shouldContain "charset=UTF-8"
    }
}
