package hr.workspace.boat4you.domains.chat.service

import hr.workspace.boat4you.domains.chat.service.AiChatService.MessageOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A route or itinerary question without a place (Mario 3.11.2026, John on Catamaran Charter Croatia: "No preference where to
 * start" and he never got an itinerary): IRIS sends the visitor to the site's page of all itineraries as a link card.
 */
class AiChatItineraryTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    private fun cardFor(
        siteId: String,
        locale: String,
    ): Pair<String, String> {
        var round = 0
        h.modelReply = {
            if (round++ == 0) h.toolCall("show_itineraries", "{}") else h.textReply("Have a look at all our itineraries below and pick one you like.")
        }
        val out = h.service.handleVisitorMessage(h.newSession(siteId = siteId, locale = locale), "Can you suggest a route for my charter?")
        val card = h.mapper.readTree(out.shouldBeInstanceOf<MessageOutcome.Replied>().messages.last().payload).path("blogs").single()
        return card.path("url").asText() to card.path("title").asText()
    }

    @Test
    fun `a route request with no place gets the site's page of all itineraries as a card, in the visitor's language`() {
        cardFor("catamaran-croatia", "en") shouldBe ("/itinerary" to "All our sailing itineraries")
        cardFor("catamaran-greece", "de") shouldBe ("/de/itinerary" to "Alle unsere Segelrouten")
        cardFor("boat4you", "hr") shouldBe ("/hr/itineraries" to "Svi naši itinerari plovidbe")
    }

    @Test
    fun `every site offers show_itineraries and the prompt says when to use it`() {
        h.service.handleVisitorMessage(h.newSession(siteId = "catamaran-croatia"), "a route please")
        h.modelTools.single()!!.map { it.path("name").asText() }.contains("show_itineraries") shouldBe true
        h.modelSystems.single().cached shouldContain "has not said where they would like to sail, or has no preference, call show_itineraries"
    }

    @Test
    fun `the prompt says a date without a year is the next time it comes, never in the past`() {
        h.service.handleVisitorMessage(h.newSession(siteId = "catamaran-croatia"), "June please")
        h.modelSystems.single().cached shouldContain "A date the visitor gives without a year is the next time it comes after today's date"
    }
}
