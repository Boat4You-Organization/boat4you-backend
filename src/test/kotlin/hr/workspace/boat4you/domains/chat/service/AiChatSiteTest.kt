package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
}
