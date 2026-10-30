package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.ChatTestHarness
import hr.workspace.boat4you.domains.chat.service.ChatTexts
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant

class CcAiWatchdogTickTest {
    private val h = ChatTestHarness()
    private val w = CcAiWatchdog(h.service, h.sessions, h.messages, h.breaker, h.settings)

    @AfterEach
    fun stop() = h.close()

    private fun waiting(minutesAgo: Long) =
        h.newSession(status = AiChatSession.STATUS_HUMAN_REQUESTED).also {
            it.handoffRequestedAt = Instant.now().minus(Duration.ofMinutes(minutesAgo))
        }

    private fun takenOver(lastUserMinutesAgo: Long) =
        h.newSession(status = AiChatSession.STATUS_HUMAN).also {
            h.addMessage(it, AiChatMessage.ROLE_ADMIN, "I'll check", lastUserMinutesAgo + 5)
            h.addMessage(it, AiChatMessage.ROLE_USER, "any news?", lastUserMinutesAgo)
        }

    @Test
    fun `holding after cc_handoff_minutes, not before, not for day-old handoffs`() {
        val due = waiting(6)
        val early = waiting(4)
        val old = waiting(25 * 60)
        w.postDueHoldings() shouldBe 1
        due.handoffHoldingAt.shouldNotBeNull()
        early.handoffHoldingAt.shouldBeNull()
        old.handoffHoldingAt.shouldBeNull()
        h.settingsValues = CcAiSettings.Values(handoffMinutes = 3)
        w.postDueHoldings() shouldBe 1
        early.handoffHoldingAt.shouldNotBeNull()
    }

    @Test
    fun `takeover keeps today's rule unless M is set`() {
        val s = takenOver(120)
        w.returnDueTakeovers() shouldBe 0
        s.status shouldBe AiChatSession.STATUS_HUMAN
        h.settingsValues = CcAiSettings.Values(takeoverReturnMinutes = 10)
        w.returnDueTakeovers() shouldBe 1
        s.status shouldBe AiChatSession.STATUS_AI
    }

    @Test
    fun `the per-conversation value wins and a fresh visitor line waits`() {
        val s = takenOver(11).also { it.aiReturnAfterMinutes = 10 }
        val fresh = takenOver(5).also { it.aiReturnAfterMinutes = 10 }
        val answered =
            takenOver(30).also {
                it.aiReturnAfterMinutes = 10
                h.addMessage(it, AiChatMessage.ROLE_ADMIN, "done", 1)
            }
        val flagged =
            takenOver(30).also {
                it.aiReturnAfterMinutes = 10
                it.flagged = true
            }
        w.returnDueTakeovers() shouldBe 1
        listOf(s, fresh, answered, flagged).map { it.status } shouldBe listOf("AI", "HUMAN", "HUMAN", "HUMAN")
    }

    @Test
    fun `one tick runs every step, a model failure still leaves an answer`() {
        h.modelReply = { throw IllegalStateException("down") }
        val back = takenOver(30).also { it.aiReturnAfterMinutes = 10 }
        val waitingSession = waiting(6)
        w.tick()
        waitingSession.handoffHoldingAt.shouldNotBeNull()
        back.status shouldBe AiChatSession.STATUS_AI
        h.transcript(back).last() shouldBe (AiChatMessage.ROLE_ASSISTANT to ChatTexts.failureApology("en"))
    }

    @Test
    fun `the watchdog runs on the API node only, every 15 s`() { // Task 14 Interfaces: the breaker and inFlight live in memory on cusma2
        CcAiWatchdog::class.java
            .getAnnotation(Profile::class.java)
            .value
            .toList() shouldBe listOf("!data-sync")
        CcAiWatchdog::class.java
            .getMethod("tick")
            .getAnnotation(Scheduled::class.java)
            ?.fixedDelayString shouldBe "\${cc.ai.watchdog-ms:15000}"
    }
}
