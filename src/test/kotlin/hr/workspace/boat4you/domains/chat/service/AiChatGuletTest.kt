package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mario 9.10.2026, a gulet is never bareboat: the chat's search rows and boat-page facts tell the model so for every
 * gulet (vesselType GULET or a model name with "gulet", as the API sends them) and for no other boat; the crew count
 * follows the API's charterType, which no longer says BAREBOAT for a gulet.
 */
class AiChatGuletTest {
    private val mapper = ObjectMapper()
    private val tools = AiChatToolExecutor("http://127.0.0.1:1", mapper)

    @Test
    fun `a gulet by vessel type or model name`() {
        assertTrue(tools.isGulet(mapper.readTree("""{"vesselType":"GULET","modelName":"Custom"}""")))
        assertTrue(tools.isGulet(mapper.readTree("""{"vesselType":"MOTOR_YACHT","modelName":"Gulet"}""")))
        assertFalse(tools.isGulet(mapper.readTree("""{"vesselType":"MOTOR_YACHT","modelName":"Princess 62"}""")))
        assertFalse(tools.isGulet(mapper.readTree("""{"vesselType":"SAILING_YACHT"}""")))
        assertFalse(tools.isGulet(mapper.readTree("""{}""")))
    }

    @Test
    fun `the crew count of a gulet the API now lists as CREWED`() {
        val y = mapper.readTree("""{"vesselType":"GULET","cabins":6,"crewNumber":4,"charterType":["CREWED"]}""")
        assertEquals("Cabins: 6, crew: 4", tools.capacityFacts(y))
    }
}
