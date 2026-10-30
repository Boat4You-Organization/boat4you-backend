package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AiChatSiteTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    @Test
    fun `a session created for a sister site keeps its site, the old widget gets boat4you`() {
        h.service.createSession("de", "Ana", "10.0.0.9", "catamaran-greece").siteId shouldBe "catamaran-greece"
        h.service.createSession("en", "Ana", "10.0.0.9").siteId shouldBe "boat4you"
    }

    @Test
    fun `a sister-site session gets its own prompt and no blog tool`() {
        h.service.handleVisitorMessage(h.newSession(siteId = "catamaran-greece"), "catamaran in July")
        h.modelSystems.single().cached shouldStartWith "You are IRIS, the AI assistant of Catamaran Charter Greece"
        h.modelTools.single()!!.map { it.path("name").asText() } shouldBe listOf("search_yachts", "request_human_agent", "save_contact")
    }

    @Test
    fun `the broker mail links the page on the conversation's own site (BACKEND-25, BACKEND-26)`() {
        val gr = "https://www.catamaran-charter-greece.com"
        AiChatService.safePageUrl("/de/catamarans/lagoon-42-a?startDate=2027-07-03", gr) shouldBe "$gr/de/catamarans/lagoon-42-a?startDate=2027-07-03"
        listOf("@evil.tld/x", "//evil.tld/x", ".evil.tld/x", "/a@evil.tld", null).forEach { AiChatService.safePageUrl(it, gr) shouldBe "" }
        AiChatService.safePageUrl("/boat/x") shouldBe "https://www.boat4you.com/boat/x" // one-argument form unchanged
    }

    @Test
    fun `a model failure answers with the apology in the session language`() {
        h.modelReply = { throw IllegalStateException("down") }
        h.service.handleVisitorMessage(h.newSession(locale = "hr"), "katamaran u srpnju")
            .shouldBeInstanceOf<AiChatService.MessageOutcome.Replied>().messages.last().content shouldBe ChatTexts.failureApology("hr")
    }

    @Test
    fun `save_contact keeps a valid phone, never puts it in the prompt, the handoff note promises email or phone`() {
        val s = h.newSession()
        var round = 0
        h.modelReply = { if (round++ == 0) h.toolCall("save_contact", """{"phone":"+385 91 123 4567"}""") else h.textReply("Thanks!") }
        h.service.handleVisitorMessage(s, "my phone is +385 91 123 4567")
        s.phone shouldBe "+385 91 123 4567"
        h.modelSystems.forEach { it.full shouldNotContain "123 4567" }
        round = 0
        h.modelReply = { if (round++ == 0) h.toolCall("save_contact", """{"phone":"call me 0800-EVIL"}""") else h.textReply("ok") }
        val other = h.newSession()
        h.service.handleVisitorMessage(other, "x")
        other.phone.shouldBeNull()
        round = 0
        h.modelReply = { if (round++ == 0) h.toolCall("request_human_agent", """{"reason":"wants a person"}""") else h.textReply("ok") }
        h.service.handleVisitorMessage(s, "a person please")
        h.modelMessages.last().toString() shouldContain "contact them by email or phone"
        h.modelMessages.last().toString() shouldNotContain "will reply right here in this chat"
    }

    @Test
    fun `a second handoff request while the first one waits mails the team once`() {
        val s = h.newSession()
        var round = 0
        h.modelReply = { if (round++ < 2) h.toolCall("request_human_agent", """{"reason":"wants a person"}""") else h.textReply("ok") }
        h.service.handleVisitorMessage(s, "a person please")
        h.awaitMails(1)
        s.status shouldBe AiChatSession.STATUS_HUMAN_REQUESTED
        h.mails.size shouldBe 1
    }
}
