package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Allison (Europe Yachts, 12.11.2026) asked for the 9th or 11th of May and read "Sure, let me check those two boats" before
 * the answer: when a tool refuses dates that are not a Saturday week, the text the model wrote next to that call is not sent.
 */
class AiChatSaturdayPreambleTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    private fun callWithText(text: String, input: String): JsonNode =
        h.mapper.readTree(
            """{"stop_reason":"tool_use","content":[{"type":"text","text":${h.mapper.writeValueAsString(text)}},""" +
                """{"type":"tool_use","id":"t1","name":"search_yachts","input":$input}]}""",
        )

    private fun replies(vararg r: JsonNode) {
        val queue = ArrayDeque(r.toList())
        h.modelReply = { queue.removeFirstOrNull() ?: h.textReply("unexpected extra call") }
    }

    @Test
    fun `the let-me-check line next to a refused non-Saturday search is not sent`() {
        val s = h.newSession()
        replies(
            callWithText("Sure, let me check those two boats for May 9 to 11.", """{"countryCode":"HR","startDate":"2027-05-09","endDate":"2027-05-11"}"""),
            h.textReply("Our charters run Saturday to Saturday only - 8-15 May or 15-22 May?"),
        )
        h.service.handleVisitorMessage(s, "Can you make it from the 9th to the 11th of May")
        h.transcript(s).last { it.first == AiChatMessage.ROLE_ASSISTANT }.second shouldBe
            "Our charters run Saturday to Saturday only - 8-15 May or 15-22 May?"
    }
}
