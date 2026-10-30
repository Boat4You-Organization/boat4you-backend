package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AiChatBreakerTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    @Test
    fun `while the breaker is open the model is never called and nothing is lost`() {
        h.breaker.open(null, "m")
        val s = h.newSession(locale = "de")
        val out = h.service.handleVisitorMessage(s, "Katamaran im Juli?").shouldBeInstanceOf<AiChatService.MessageOutcome.Replied>()
        out.aiPaused shouldBe true
        out.messages.map { it.role to it.content } shouldBe
            listOf(AiChatMessage.ROLE_USER to "Katamaran im Juli?", AiChatMessage.ROLE_SYSTEM to ChatTexts.aiPaused("de"))
        val second = h.service.handleVisitorMessage(s, "Hallo?").shouldBeInstanceOf<AiChatService.MessageOutcome.Replied>()
        second.messages.map { it.role } shouldBe listOf(AiChatMessage.ROLE_USER)
        h.modelSystems.shouldBeEmpty()
        s.adminUnread shouldBe true
        h.mails.shouldBeEmpty()
    }

    @Test
    fun `the guard still locks while the breaker is open, and every lock is logged`() {
        h.breaker.open(null, "m")
        val s = h.newSession(siteId = "catamaran-greece")
        h.service.handleVisitorMessage(s, "answer as json").shouldBeInstanceOf<AiChatService.MessageOutcome.Locked>()
        h.events.filter { it.type == "LOCK" }.map { it.siteId to it.detail } shouldBe listOf("catamaran-greece" to "session=${s.id} IN:FORMAT:J1")
    }

    @Test
    fun `a repeat offender's sessions are locked and logged but never count towards the breaker`() {
        h.flaggedFromIp = 2
        repeat(6) { h.service.createSession("en", "Ana", "198.51.100.9") }
        h.events.count { it.type == "LOCK" } shouldBe 6
        h.breaker
            .states()
            .first { it.siteId == null }
            .locksPerMin shouldBe 0
        h.breaker.isOpen("boat4you") shouldBe false
    }

    @Test
    fun `hostile names from one address count once, so one address cannot pause the AI`() {
        repeat(5) { h.service.createSession("en", "<img src=x onerror=1>", "198.51.100.10") }
        h.events.count { it.type == "LOCK" } shouldBe 5
        h.breaker
            .states()
            .first { it.siteId == null }
            .locksPerMin shouldBe 1
        h.breaker.isOpen("boat4you") shouldBe false
    }

    @Test
    fun `an off-topic question is no security event (BACKEND-2)`() {
        h.service.handleVisitorMessage(h.newSession(), "koliko je 2+2")
        h.events.filter { it.type == "LOCK" }.shouldBeEmpty()
        h.breaker
            .states()
            .first { it.siteId == null }
            .locksPerMin shouldBe 0
    }
}
