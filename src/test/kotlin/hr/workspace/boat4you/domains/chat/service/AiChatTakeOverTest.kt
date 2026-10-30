package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** P2 "Preuzmi" and the broker reply's conditional UPDATEs. */
class AiChatTakeOverTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    @Test
    fun `take over makes the AI stop without a message`() {
        val s = h.newSession()
        h.service.takeOver(s) shouldBe true
        s.status shouldBe AiChatSession.STATUS_HUMAN
        s.takenOverAt.shouldNotBeNull()
        h.transcript(s).shouldBeEmpty()
        h.service.handleVisitorMessage(s, "Is anyone there?")
        h.modelSystems.shouldBeEmpty()
        h.awaitMails(1)
        h.mails shouldHaveSize 1
    }

    @Test
    fun `take over never touches a LOCKED or deleted session`() {
        val locked = h.newSession(status = AiChatSession.STATUS_LOCKED)
        h.service.takeOver(locked) shouldBe false
        locked.status shouldBe AiChatSession.STATUS_LOCKED
        val deleted = h.newSession().apply { deletedAt = Instant.now() }
        h.service.takeOver(deleted) shouldBe false
        deleted.status shouldBe AiChatSession.STATUS_AI
    }

    @Test
    fun `a reply writes conditional UPDATEs, never a whole-row save of the loaded session`() {
        val s = h.newSession()
        h.addMessage(s, AiChatMessage.ROLE_USER, "Bok", 1)
        val saves = h.sessionSavedInTx.size
        h.service.adminReply(s, "Pozdrav", authorUserId = 7)
        h.humanReplyUpdates shouldContainExactly listOf("markHumanReply", "stampHumanReply")
        h.sessionSavedInTx shouldHaveSize saves
        s.status shouldBe AiChatSession.STATUS_HUMAN
        s.adminUnread shouldBe false
    }

    @Test
    fun `a reply before the visitor wrote stamps no response`() {
        val s = h.newSession()
        h.service.adminReply(s, "Hello!", authorUserId = 7)
        h.humanReplyUpdates shouldContainExactly listOf("markHumanReply")
    }
}
