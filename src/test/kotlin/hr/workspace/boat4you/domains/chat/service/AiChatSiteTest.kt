package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.shouldBe
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
}
