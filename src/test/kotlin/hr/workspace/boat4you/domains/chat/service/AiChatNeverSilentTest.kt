package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AiChatNeverSilentTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    private fun waiting(
        locale: String = "de",
        minutesAgo: Long = 6,
    ) = h.newSession(status = AiChatSession.STATUS_HUMAN_REQUESTED, locale = locale).also {
        it.handoffRequestedAt = Instant.now().minus(Duration.ofMinutes(minutesAgo))
    }

    @Test
    fun `the holding message goes out once, asks for a contact only when none is known`() {
        val s = waiting()
        h.service.postHandoffHolding(s) shouldBe true
        h.service.postHandoffHolding(s) shouldBe false
        h.transcript(s).last() shouldBe (AiChatMessage.ROLE_ASSISTANT to ChatTexts.handoffHolding("de", hasContact = false))
        s.handoffHoldingAt.shouldNotBeNull()
        s.status shouldBe AiChatSession.STATUS_HUMAN_REQUESTED
        val withMail = waiting("en").also { it.visitorEmail = "ana@example.com" }
        h.service.postHandoffHolding(withMail)
        h.transcript(withMail).last().second shouldBe ChatTexts.handoffHolding("en", hasContact = true)
        h.modelSystems.shouldBeEmpty()
    }

    @Test
    fun `after the holding message the AI keeps answering, the broker gets one mail per burst`() {
        val s = waiting().also { h.service.postHandoffHolding(it) }
        h.service.handleVisitorMessage(s, "Hat das Boot eine Klimaanlage?")
        h.service.handleVisitorMessage(s, "Und WLAN?")
        h.modelSystems shouldHaveSize 2
        h.modelSystems.last().tail shouldContain AiChatService.HANDOFF_NOTE
        s.status shouldBe AiChatSession.STATUS_HUMAN_REQUESTED
        h.awaitMails(1)
        h.mails shouldHaveSize 1
    }

    @Test
    fun `before the holding message a waiting session gets no AI (today)`() {
        val s = waiting(minutesAgo = 2)
        h.service.handleVisitorMessage(s, "hello?")
        h.modelSystems.shouldBeEmpty()
    }

    @Test
    fun `a flagged or HUMAN session never gets the holding message or the AI back`() {
        h.service.postHandoffHolding(waiting().also { it.flagged = true }) shouldBe false
        h.service.postHandoffHolding(h.newSession(status = AiChatSession.STATUS_HUMAN)) shouldBe false
        val flaggedHuman = h.newSession(status = AiChatSession.STATUS_HUMAN).also { it.flagged = true }
        h.service.returnToAi(flaggedHuman) shouldBe false
        flaggedHuman.status shouldBe AiChatSession.STATUS_HUMAN
        h.service.returnToAi(h.newSession(status = AiChatSession.STATUS_LOCKED)) shouldBe false
        h.service.returnToAi(h.newSession(status = AiChatSession.STATUS_HUMAN).also { it.deletedAt = Instant.now() }) shouldBe false
        val flaggedAfterHolding =
            waiting().also {
                it.handoffHoldingAt = Instant.now()
                it.flagged = true
            }
        h.service.handleVisitorMessage(flaggedAfterHolding, "hello?") // stored, broker mailed as today, no AI (ADMIN-9)
        h.modelSystems.shouldBeEmpty()
    }

    @Test
    fun `returning to AI answers the pending visitor message once and the AI sees Mario's line`() {
        val s = h.newSession(status = AiChatSession.STATUS_HUMAN)
        h.addMessage(s, AiChatMessage.ROLE_ADMIN, "I'll check the price", 30)
        h.addMessage(s, AiChatMessage.ROLE_USER, "any news?", 12)
        h.service.returnToAi(s) shouldBe true
        s.status shouldBe AiChatSession.STATUS_AI
        s.aiReturnedAt.shouldNotBeNull()
        h.modelSystems shouldHaveSize 1
        h.modelMessages.single().toString() shouldContain "[Team reply] I'll check the price"
        h.transcript(s).last().first shouldBe AiChatMessage.ROLE_ASSISTANT
    }
}
