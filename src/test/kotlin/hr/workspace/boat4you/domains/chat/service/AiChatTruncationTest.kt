package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A reply is never a bare announcement (Nihad 30.10.2026: "running all five weeks now", six times, with no results and two
 * "technical hiccup" lines): a reply cut off by max_tokens is asked again with more room, and when the tool rounds run out
 * the model answers once more with tools switched off, from what it already found.
 */
class AiChatTruncationTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    private fun cut(text: String): JsonNode =
        h.mapper.readTree(
            """{"stop_reason":"max_tokens","content":[{"type":"text","text":${h.mapper.writeValueAsString(text)}},""" +
                """{"type":"tool_use","id":"t9","name":"search_yachts","input":{"countryCode":"HR"}}]}""",
        )

    private fun replies(vararg r: JsonNode) {
        val queue = ArrayDeque(r.toList())
        h.modelReply = { queue.removeFirstOrNull() ?: h.textReply("unexpected extra call") }
    }

    private fun lastAssistant(s: AiChatSession) = h.transcript(s).last { it.first == AiChatMessage.ROLE_ASSISTANT }.second

    @Test
    fun `a reply cut off by max_tokens is asked again with more room and the announcement is not sent`() {
        val s = h.newSession()
        replies(cut("Running all five weeks now."), h.textReply("Here are the boats for your weeks."))
        h.service.handleVisitorMessage(s, "Any week Sat to Sat between 18 Sep and 23 Oct 2027")
        lastAssistant(s) shouldBe "Here are the boats for your weeks."
        h.modelMaxTokens shouldContainExactly listOf(1200, 2400)
    }

    @Test
    fun `a reply cut off again while calling tools is an honest apology, never the bare announcement`() {
        val s = h.newSession()
        replies(cut("Running all five weeks now."), cut("Running all five weeks now."))
        h.service.handleVisitorMessage(s, "Go ahead")
        lastAssistant(s) shouldBe ChatTexts.failureApology("en")
    }

    @Test
    fun `when the tool rounds run out the model answers once more with tools off, from what it found`() {
        val s = h.newSession()
        val search = h.toolCall("search_yachts", """{"countryCode":"HR","startDate":"2027-09-18","endDate":"2027-09-25"}""")
        replies(search, search, search, search, search, h.textReply("Here is what I found so far."))
        h.service.handleVisitorMessage(s, "All five weeks please")
        lastAssistant(s) shouldBe "Here is what I found so far."
        h.modelToolsOff shouldContainExactly listOf(false, false, false, false, false, true)
        // the last call answers every pending tool call, so the conversation stays valid for the API
        val last = h.modelMessages.last()
        last.last().path("role").asText() shouldBe "user"
        last.last().path("content").first().path("type").asText() shouldBe "tool_result"
        last.last().path("content").first().path("tool_use_id").asText() shouldBe "t1"
    }
}
