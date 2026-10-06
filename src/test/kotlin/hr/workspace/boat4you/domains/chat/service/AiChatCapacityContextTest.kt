package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The AI chat's "current page" capacity line (capacity contract v1, 2.6): built from the detail JSON's `capacity` block,
 * unknown parts left out, berths never called "sleeps up to {maxPersons}", crew only for a crewed charter, partner notes
 * only the API's sanitized ones.
 */
class AiChatCapacityContextTest {
    private val mapper = ObjectMapper()
    private val tools = AiChatToolExecutor("http://127.0.0.1:1", mapper)

    @Test
    fun `Dione II (MMK) - notes split, crew for a crewed listing`() {
        val y =
            mapper.readTree(
                """
                {"cabins":6,"berths":13,"wc":6,"maxPersons":14,"crewNumber":1,"charterType":["CREWED","BAREBOAT"],
                 "capacity":{"cabins":{"value":6,"note":"(5 double +1 for the hostess + 1 bow/skippers cabin)","split":null},
                   "berths":{"value":13,"note":"(12 pax + 1 Crew)","split":{"guests":12,"inCabins":null,"saloon":null,"crew":1,"skipper":null}},
                   "heads":{"value":6,"note":"(5+1 for the crew)","split":{"guests":5,"inCabins":null,"saloon":null,"crew":1,"skipper":null}},
                   "crewCabins":null,"crewHeads":null,"showers":null,"crewShowers":null,"maxPersons":14,"recommendedPersons":null,"crewNumber":1}}
                """.trimIndent(),
            )
        assertEquals("Cabins: 6, berths: 13 (12 + 1 crew), WC: 6 (5 + 1 crew), max people on board: 14, crew: 1", tools.capacityFacts(y))
        assertEquals(
            "Partner capacity notes (verbatim): cabins \"(5 double +1 for the hostess + 1 bow/skippers cabin)\"; " +
                "berths \"(12 pax + 1 Crew)\"; WC \"(5+1 for the crew)\"",
            tools.capacityNotes(y),
        )
    }

    @Test
    fun `Marea (NauSys) - crew cabins and crew WC labelled, recommended persons`() {
        val y =
            mapper.readTree(
                """
                {"cabins":5,"berths":12,"wc":5,"maxPersons":12,"crewNumber":2,"charterType":["ALL_INCLUSIVE","CREWED"],
                 "capacity":{"cabins":{"value":5,"note":null,"split":null},
                   "berths":{"value":12,"note":null,"split":{"guests":null,"inCabins":10,"saloon":null,"crew":2,"skipper":null}},
                   "heads":{"value":5,"note":null,"split":null},
                   "crewCabins":2,"crewHeads":2,"showers":null,"crewShowers":null,"maxPersons":12,"recommendedPersons":10,"crewNumber":2}}
                """.trimIndent(),
            )
        assertEquals(
            "Cabins: 5 (crew cabins: 2), berths: 12 (10 in cabins + 2 crew), WC: 5 (crew WC: 2), max people on board: 12 (recommended 10), crew: 2",
            tools.capacityFacts(y),
        )
        assertNull(tools.capacityNotes(y))
    }

    @Test
    fun `unknown max people and a bareboat listing - nothing invented`() {
        val y =
            mapper.readTree(
                """
                {"cabins":3,"berths":8,"wc":2,"maxPersons":null,"crewNumber":1,"charterType":["BAREBOAT"],
                 "capacity":{"cabins":{"value":3,"note":null,"split":null},
                   "berths":{"value":8,"note":null,"split":{"guests":null,"inCabins":6,"saloon":2,"crew":null,"skipper":null}},
                   "heads":{"value":2,"note":null,"split":null},
                   "crewCabins":null,"crewHeads":null,"showers":null,"crewShowers":null,"maxPersons":null,"recommendedPersons":null,"crewNumber":1}}
                """.trimIndent(),
            )
        assertEquals("Cabins: 3, berths: 8 (6 in cabins + 2 in the saloon), WC: 2", tools.capacityFacts(y))
    }

    @Test
    fun `an answer without the capacity block falls back to the positive flat figures`() {
        val y = mapper.readTree("""{"cabins":4,"berths":0,"wc":2,"maxPersons":null,"charterType":["BAREBOAT"]}""")
        assertEquals("Cabins: 4, WC: 2", tools.capacityFacts(y))
        assertNull(tools.capacityFacts(mapper.readTree("""{"cabins":null}""")))
    }
}
