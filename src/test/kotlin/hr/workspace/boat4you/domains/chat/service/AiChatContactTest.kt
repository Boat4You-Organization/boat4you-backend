package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The chat collects a way to reach the visitor (Mario 31.10.2026: Alison, Amanda and Nihad left requests and no contact):
 * an e-mail or phone in any visitor message is kept, also while a handoff waits and the AI is silent; the first reply with
 * boats carries the contact form once; the form's values are checked and stored; every change reaches the CC contact.
 */
class AiChatContactTest {
    private val row = """{"slug":"lagoon-42-blue","name":"Blue","modelName":"Lagoon 42","clientPriceEur":200,"numberOfDays":7,"cabins":4}"""
    private val h = ChatTestHarness(fleetRow = row)

    @AfterEach
    fun stop() = h.close()

    private val search = """{"countryCode":"HR","startDate":"2027-09-18","endDate":"2027-09-25"}"""

    private fun replies(vararg r: com.fasterxml.jackson.databind.JsonNode) {
        val queue = ArrayDeque(r.toList())
        h.modelReply = { queue.removeFirstOrNull() ?: h.textReply("unexpected extra call") }
    }

    private fun lastPayload(s: AiChatSession) = h.stored.last { it.sessionId == s.id && it.role == AiChatMessage.ROLE_ASSISTANT }.payload.orEmpty()

    @Test
    fun `an email typed while a handoff waits is kept and synced, and the holding line does not ask for it again`() {
        val s =
            h.newSession(status = AiChatSession.STATUS_HUMAN_REQUESTED).also {
                it.handoffRequestedAt = Instant.now().minus(Duration.ofMinutes(6))
            }
        h.service.handleVisitorMessage(s, "kruger78@gmail.com")
        s.visitorEmail shouldBe "kruger78@gmail.com"
        h.contactSyncs shouldContainExactly listOf(s.id!!)
        h.service.postHandoffHolding(s) shouldBe true
        h.transcript(s).last().second shouldBe ChatTexts.handoffHolding("en", hasContact = true)
        h.modelSystems.shouldBeEmpty()
    }

    @Test
    fun `a phone in a normal message is kept, a known email or phone is never replaced`() {
        val s = h.newSession().also { it.phone = "+385 1 111 1111" }
        h.service.handleVisitorMessage(s, "my mail is ana@example.com and phone 091 234 5678")
        s.visitorEmail shouldBe "ana@example.com"
        s.phone shouldBe "+385 1 111 1111"
        h.contactSyncs shouldContainExactly listOf(s.id!!)
        h.service.handleVisitorMessage(s, "no contact details here")
        h.contactSyncs shouldContainExactly listOf(s.id!!)
    }

    @Test
    fun `the first reply with boats carries the contact form once, never when a contact is known`() {
        val s = h.newSession()
        replies(h.toolCall("search_yachts", search), h.textReply("Here are boats. May I have your email and phone?"))
        h.service.handleVisitorMessage(s, "Split, 18-25 Sept 2027")
        lastPayload(s) shouldContain "\"contactForm\":true"
        replies(h.toolCall("search_yachts", search), h.textReply("More boats."))
        h.service.handleVisitorMessage(s, "more please")
        lastPayload(s) shouldNotContain "contactForm"

        val known = h.newSession().also { it.visitorEmail = "ana@example.com" }
        replies(h.toolCall("search_yachts", search), h.textReply("Here are boats."))
        h.service.handleVisitorMessage(known, "Split, 18-25 Sept 2027")
        lastPayload(known) shouldNotContain "contactForm"
    }

    @Test
    fun `the contact form stores a checked email and a normalised phone and syncs the contact`() {
        val s = h.newSession()
        h.service.setVisitorContact(s, " kruger78@gmail.com ", "0044 7700-900 123") shouldBe AiChatService.ContactOutcome.OK
        s.visitorEmail shouldBe "kruger78@gmail.com"
        s.phone shouldBe "+44 7700 900 123"
        h.contactSyncs shouldContainExactly listOf(s.id!!)
        h.service.setVisitorContact(s, "nope", null) shouldBe AiChatService.ContactOutcome.INVALID_EMAIL
        h.service.setVisitorContact(s, null, "12") shouldBe AiChatService.ContactOutcome.INVALID_PHONE
        h.service.setVisitorContact(s, " ", null) shouldBe AiChatService.ContactOutcome.EMPTY
        h.service.setVisitorContact(h.newSession().also { it.flagged = true }, "a@b.com", null) shouldBe AiChatService.ContactOutcome.LOCKED
    }

    @Test
    fun `IRIS is told a contact is on file, so it never asks for it again`() {
        h.service.handleVisitorMessage(h.newSession().also { it.visitorEmail = "ana@example.com" }, "hello")
        h.modelSystems.last().tail shouldContain "CONTACT ON FILE: email yes, phone no"
        h.service.handleVisitorMessage(h.newSession(), "hello")
        h.modelSystems.last().tail shouldNotContain "CONTACT ON FILE"
    }

    @Test
    fun `the form waits while IRIS answers, so the reply cannot overwrite what it stored`() {
        val s = h.newSession()
        var during: AiChatService.ContactOutcome? = null
        h.modelReply = {
            during = h.service.setVisitorContact(s, "ana@example.com", null)
            h.textReply("ok")
        }
        h.service.handleVisitorMessage(s, "hello")
        during shouldBe AiChatService.ContactOutcome.BUSY
        h.service.setVisitorContact(s, "ana@example.com", null) shouldBe AiChatService.ContactOutcome.OK
    }

    @Test
    fun `an over-long address is refused, never a failed message`() {
        val long = "x".repeat(250) + "@example.com"
        val s = h.newSession()
        h.service.handleVisitorMessage(s, "my mail is $long")
        s.visitorEmail shouldBe null
        h.service.setVisitorContact(s, long, null) shouldBe AiChatService.ContactOutcome.INVALID_EMAIL
    }
}
