package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Mario 6.11.2026: "Bali 4.6 €13,357, Lagoon 51 €18,000 approx" - the second figure came from no search. A reply whose
 * euro amounts are not in this chat's tool results is asked once more with tools off and told which amounts; a second
 * reply that still has them loses the sentences that carry them. The boats already shown are listed for the model so a
 * later turn quotes them right.
 */
class AiChatPriceGuardTest {
    private val h = ChatTestHarness(fleetRow = ROW)

    @AfterEach
    fun stop() = h.close()

    private fun replies(vararg r: JsonNode) {
        val queue = ArrayDeque(r.toList())
        h.modelReply = { queue.removeFirstOrNull() ?: h.textReply("unexpected extra call") }
    }

    private fun lastAssistant(s: AiChatSession) = h.transcript(s).last { it.first == AiChatMessage.ROLE_ASSISTANT }.second

    private val search = """{"countryCode":"GR","startDate":"2027-06-19","endDate":"2027-06-26"}"""

    @Test
    fun `a reply with the search's own totals goes out as it is, in one call after the search`() {
        val s = h.newSession()
        replies(h.toolCall("search_yachts", search), h.textReply("The Bali 4.8 Calypso is €13,357 for the week."))
        h.service.handleVisitorMessage(s, "Lagoon 46 or similar, 19-26 June 2027")
        lastAssistant(s) shouldBe "The Bali 4.8 Calypso is €13,357 for the week."
        h.modelToolsOff shouldContainExactly listOf(false, false)
    }

    @Test
    fun `an invented amount sends the reply back once, with tools off and the amount named`() {
        val s = h.newSession()
        replies(
            h.toolCall("search_yachts", search),
            h.textReply("The Bali 4.8 is €13,357. A Lagoon 51 would be about €18,000."),
            h.textReply("The Bali 4.8 Calypso is €13,357 for the week."),
        )
        h.service.handleVisitorMessage(s, "Lagoon 46 or similar, 19-26 June 2027")
        lastAssistant(s) shouldBe "The Bali 4.8 Calypso is €13,357 for the week."
        h.modelToolsOff shouldContainExactly listOf(false, false, true)
        h.modelMessages.last().last().path("content").asText() shouldContain "18000"
    }

    @Test
    fun `a second reply that still invents loses the sentences with that amount`() {
        val s = h.newSession()
        replies(
            h.toolCall("search_yachts", search),
            h.textReply("The Bali 4.8 is €13,357. A Lagoon 51 would be about €18,000."),
            h.textReply("The Bali 4.8 is €13,357. The Lagoon 51 is roughly €18,000. Shall I check another week?"),
        )
        h.service.handleVisitorMessage(s, "Lagoon 46 or similar, 19-26 June 2027")
        lastAssistant(s) shouldBe "The Bali 4.8 is €13,357. Shall I check another week?"
    }

    @Test
    fun `the boats shown earlier in the chat are known amounts and are listed for the model`() {
        val s = h.newSession()
        h.addMessage(s, AiChatMessage.ROLE_USER, "Catamaran in Lefkas 19-26 June 2027", 5)
        h.addMessage(s, AiChatMessage.ROLE_ASSISTANT, "Here are some boats.", 4).apply {
            payload = """{"yachts":[{"name":"Lagoon 51 Navya","slug":"lagoon-51-navya-1","totalPriceEur":14582,"location":"Lefkas","startDate":"2027-06-19","endDate":"2027-06-26","cabins":6}]}"""
        }
        replies(h.textReply("The Lagoon 51 Navya was €14,582 for that week."))
        h.service.handleVisitorMessage(s, "How much was the Lagoon 51?")
        lastAssistant(s) shouldBe "The Lagoon 51 Navya was €14,582 for that week."
        h.modelSystems.last().full shouldContain "Lagoon 51 Navya"
        h.modelSystems.last().full shouldContain "14582"
        h.modelSystems.last().full shouldNotContain "lagoon-51-navya-1&"
    }

    private companion object {
        const val ROW =
            """{"slug":"bali-4-8-calypso-5000","name":"Calypso","modelName":"Bali 4.8","clientPriceEur":1908.143,"numberOfDays":7,"offerDateFrom":"2027-06-19","offerDateTo":"2027-06-26","location":{"name":"D-Marin Marina Lefkas","countryCode":"GR"}}"""
    }
}
