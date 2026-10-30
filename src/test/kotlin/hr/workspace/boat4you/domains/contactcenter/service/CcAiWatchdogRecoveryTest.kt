package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.ChatTestHarness
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CcAiWatchdogRecoveryTest {
    private val h = ChatTestHarness()
    private val w = CcAiWatchdog(h.service, h.sessions, h.messages, h.breaker, h.settings)

    @AfterEach
    fun stop() = h.close()

    private fun pending(
        status: String = AiChatSession.STATUS_AI,
        minutesAgo: Long = 2,
        flagged: Boolean = false,
    ) = h.newSession(status = status).also {
        it.flagged = flagged
        h.addMessage(it, AiChatMessage.ROLE_USER, "catamaran in July?", minutesAgo)
    }

    @Test
    fun `an AI session with a fresh unanswered visitor message is answered once`() {
        val s = pending()
        w.recoverAfterRestart() shouldBe 1
        w.recoverAfterRestart() shouldBe 0
        h.transcript(s).map { it.first } shouldBe listOf(AiChatMessage.ROLE_USER, AiChatMessage.ROLE_ASSISTANT)
    }

    @Test
    fun `recovery skips LOCKED, flagged, HUMAN, HUMAN_REQUESTED, answered and old sessions`() {
        pending(status = AiChatSession.STATUS_LOCKED, flagged = true)
        pending(flagged = true)
        pending(status = AiChatSession.STATUS_HUMAN)
        pending(status = AiChatSession.STATUS_HUMAN_REQUESTED)
        pending(minutesAgo = 6)
        pending().also { h.addMessage(it, AiChatMessage.ROLE_ASSISTANT, "answered", 1) }
        w.recoverAfterRestart() shouldBe 0
        h.modelSystems.shouldBeEmpty()
    }

    @Test
    fun `recovery waits while the breaker is open`() {
        pending()
        h.breaker.open(null, "m")
        w.recoverAfterRestart() shouldBe 0
    }

    @Test
    fun `a message being answered right now is not answered twice`() {
        val s = h.newSession()
        val release = CountDownLatch(1)
        h.modelReply = {
            release.await(5, TimeUnit.SECONDS)
            h.textReply("Here you go")
        }
        val live = thread { h.service.handleVisitorMessage(s, "catamaran in July?") }
        while (h.modelSystems.isEmpty()) Thread.sleep(10)
        w.recoverAfterRestart() shouldBe 0
        release.countDown()
        live.join()
        h.transcript(s).count { it.first == AiChatMessage.ROLE_ASSISTANT } shouldBe 1
    }
}
