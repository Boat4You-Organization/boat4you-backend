package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate

/** Mario's e-mails about calls (design §2.5): the summary and a link, never the transcript. */
class CallNotifierTest {
    private data class Mail(
        val to: List<String>,
        val subject: String,
        val template: String,
        val vars: Map<String, Any?>,
    )

    private val mails = mutableListOf<Mail>()
    private val users = mock(UserRepository::class.java).also { `when`(it.findAllAdminEmailAddresses()).thenReturn(listOf("mario@boat4you.com")) }
    private val notifier = CallNotifier(mock(EmailService::class.java), users).also { n -> n.send = { to, s, t, v -> mails += Mail(to, s, t, v) } }

    private val inquiry =
        Inquiry().apply {
            id = 5
            name = "Ana"
            surname = "Horvat"
            site = "italy"
            phoneE164 = "+385912345678"
        }
    private val call =
        InquiryCall().apply {
            id = 77
            inquiryId = 5
            outcome = CallOutcome.READY_TO_BOOK
            summary = "Spremna rezervirati Lagoon 46."
            data = mapOf("callback_time" to "sutra u 10")
            transcript = listOf(mapOf("role" to "user", "message" to "TAJNA REČENICA IZ RAZGOVORA"))
        }

    @Test
    fun `the notification e-mail carries the summary but never the transcript`() {
        notifier.outcome(call, inquiry)
        val m = mails.single()
        m.to shouldBe listOf("mario@boat4you.com")
        m.template shouldBe "email/callsNotification"
        m.subject shouldBe "Nika: spreman rezervirati – Ana Horvat"
        m.vars["summary"] shouldBe "Spremna rezervirati Lagoon 46."
        m.vars["site"] shouldBe "Catamaran Charter Italy"
        m.vars["callbackTime"] shouldBe "sutra u 10"
        m.vars["adminUrl"] shouldBe "https://admin.boat4you.com/call-center/calls/77"
        m.vars.values.joinToString(" ") shouldNotContain "TAJNA"
        m.subject shouldNotContain "TAJNA"
    }

    @Test
    fun `limitReached mails once per day`() {
        notifier.limitReached(LocalDate.of(2026, 11, 3))
        notifier.limitReached(LocalDate.of(2026, 11, 3))
        notifier.limitReached(LocalDate.of(2026, 11, 4))
        mails.size shouldBe 2
    }

    @Test
    fun `a technical failure names the client and the error`() {
        notifier.technicalFailure(call.apply { error = "ElevenLabs POST answered 500" }, inquiry)
        val m = mails.single()
        m.subject shouldContain "tehnička greška"
        m.vars["summary"].toString() shouldContain "500"
    }

    @Test
    fun `nothing is sent when there is no admin address`() {
        `when`(users.findAllAdminEmailAddresses()).thenReturn(emptyList())
        notifier.outcome(call, inquiry)
        mails.size shouldBe 0
    }

    @Test
    fun `a failing mail never breaks the caller, so the call result is still stored`() {
        val failing = CallNotifier(mock(EmailService::class.java), users).also { it.send = { _, _, _, _ -> throw IllegalStateException("smtp down") } }
        shouldNotThrowAny {
            failing.outcome(call, inquiry)
            failing.technicalFailure(call, inquiry)
            failing.lost(call, inquiry)
            failing.limitReached(LocalDate.of(2026, 11, 5))
        }
    }

    @Test
    fun `a lost call tells Mario it was not dialled again`() {
        notifier.lost(call, inquiry)
        val mail = mails.single()
        mail.subject shouldContain "poziv bez rezultata"
        mail.vars["summary"].toString() shouldContain "Nazovi sad"
    }
}
