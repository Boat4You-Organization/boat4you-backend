package hr.workspace.boat4you.common

import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openapitools.client.mmk.model.Yacht

class MatchersTests {
    @Test
    fun `test name matching for extras`() {
        assertTrue(Matchers.extrasNameMatch(setOf("APA"), "APA"))
        assertTrue(Matchers.extrasNameMatch(setOf("case:APA"), "APA"))
        assertFalse(Matchers.extrasNameMatch(setOf("case:APA"), "Napa"))
        assertFalse(Matchers.extrasNameMatch(setOf("case:APA"), "APA 35-45%"))
        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:APA"), "APA 35-45%"))
        assertFalse(Matchers.extrasNameMatch(setOf("case:APA"), "APA je nesto oko 35-45%"))

        assertFalse(Matchers.extrasNameMatch(setOf("case:SUP"), "stand up paddle (SUP)"))
        assertTrue(Matchers.extrasNameMatch(setOf("stand up paddle"), "stand up paddle (SUP)"))
        assertTrue(Matchers.extrasNameMatch(setOf("stand up paddle"), "Stand-up Paddle"))
        assertFalse(Matchers.extrasNameMatch(setOf("case:SUP"), "SUP / day"))
        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:SUP"), "SUP / day"))

        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:Skipper"), "Skipper obligatory "))
        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:Skipper"), "Skipper (+ provisioning, + damage waiver) FDF"))
        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:Skipper"), "SoMi Skipper"))

        assertFalse(Matchers.extrasNameMatch(setOf("skipper"), "APA Paradise 5"))

        assertFalse(Matchers.extrasNameMatch(setOf("case:SUP"), "SUP Stand up paddle board (deposit 300 €)"))
        assertFalse(Matchers.extrasNameMatch(setOf("case:SUP"), "Sup"))
        assertTrue(Matchers.extrasNameMatch(setOf("case-substring:SUP"), "SUP Stand up paddle board (deposit 300 €)"))

        // bug where Diving was mapped to dinghy with threshold of 0.80, but it should not match
        assertFalse(Matchers.extrasNameMatch(setOf("dinghy"), "Diving"))
        assertTrue(Matchers.extrasNameMatch(setOf("diving equipment", "diving"), "Diving"))

        assertFalse(Matchers.extrasNameMatch(setOf("pets"), "Petrol"))
        assertFalse(Matchers.extrasNameMatch(setOf("pets"), "Penta"))
    }

    @Test
    fun `issues`() {
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable canoe-kayak"))
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable dinghy with engine"))
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable tube"))
        assertFalse(Matchers.extrasNameMatch(setOf("permit for montenegro"), "Permit for international waters"))
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:late check-in", "late boarding"), "Catering"))
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:late check-in", "late boarding"), "Wake Board"))
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:early check-in", "early boarding"), "Late check-in"))
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:early check-in", "early boarding"), "late check-in"))
        assertFalse(Matchers.extrasNameMatch(setOf("refundable security deposit"), "Refuel service"))
        assertFalse(Matchers.extrasNameMatch(setOf("refundable security deposit"), "Refueling service"))
    }

    @Test
    fun `issue early check-in - late chekin`() {
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:early check-in"), "early check in"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:late boarding"), "Late Boarding (over 40 feet)"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:early check-in"), " *Early Check-in "))
    }

    @Test
    fun `issues BBQ, Skipper with Charter pack`() {
        assertTrue(
            Matchers.extrasNameMatch(
                setOf("bbq", "barbecue", "case-substring:BBQ"),
                "Charter Pack (fully fuelled outboard engine, gas for kitchen and BBQ (if any), final cleaning)",
            ),
        )
        assertFalse(
            Matchers.extrasNameMatch(
                setOf("charter pack", "final cleaning", "transit log"),
                "Charter Pack (fully fuelled outboard engine, gas for kitchen and BBQ (if any), final cleaning)",
            ),
        )
        assertTrue(
            Matchers.extrasNameMatch(
                setOf("charter pack", "final cleaning", "transit log", "token-match:charter pack"),
                "Charter Pack (fully fuelled outboard engine, gas for kitchen and BBQ (if any), final cleaning)",
            ),
        )
        assertFalse(
            Matchers.extrasNameMatch(
                setOf("bbq", "barbecue", "case-substring:BBQ", "not:charter pack", "not:transit log"),
                "Charter Pack (fully fuelled outboard engine, gas for kitchen and BBQ (if any), final cleaning)",
            ),
        )
        assertFalse(
            Matchers.extrasNameMatch(
                setOf("case-substring:SUP", "full-match:SUP", "case:SUP", "stand up paddle", "not:charter pack", "not:transit log", "not:comfort pack"),
                " Comfort Pack The Sun (Cleaning + Crew + Bed Linen + Towels + Beach Towels + 1 Kayak + 2 SUP + 2 seabob light + Inflatable floating platform + Outboard with engine)",
            ),
        )
    }

    @Test
    fun `issue pets onboard with sleep onboard`() {
        assertFalse(Matchers.extrasNameMatch(setOf("pets", "pets on board"), "Sleep onboard"))
    }

    @Test
    fun `fishing - diving equipment`() {
        assertFalse(Matchers.extrasNameMatch(setOf("fishing equipment"), "Diving equipment"))
    }

    @Test
    fun `arrival - adrenaline pack`() {
        assertFalse(Matchers.extrasNameMatch(setOf("adrenalin pack"), "Arrival pack"))
    }

    @Test
    fun `Permit for Montenegro`() {
        assertFalse(Matchers.extrasNameMatch(setOf("permit for montenegro"), "Permit"))
        assertFalse(Matchers.extrasNameMatch(setOf("permit for montenegro"), "Permit for international waters"))
        assertFalse(Matchers.extrasNameMatch(setOf("permit for montenegro"), "Permit for leaving Croatian waters"))
    }

    @Test
    fun `inflatable lifejacket`() {
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable Canoe"))
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable Kayak"))
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable tube"))
        assertTrue(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable lifevest"))
        assertFalse(Matchers.extrasNameMatch(setOf("inflatable lifejacket"), "Inflatable tube"))
    }

    @Test
    fun `water skiis`() {
        assertFalse(Matchers.extrasNameMatch(setOf("water skies", "water ski"), "Watermaker"))
        assertTrue(Matchers.extrasNameMatch(setOf("water skies", "token-match:water skis"), "Water skis with rope (adults)"))
    }

    @Test
    fun `subwing`() {
        assertFalse(Matchers.extrasNameMatch(setOf("subwing"), "Scuba Diving"))
    }

    @Test
    fun `diving`() {
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:diving"), "Scuba Diving"))
    }

    @Test
    fun `efoil`() {
        assertTrue(Matchers.extrasNameMatch(setOf("efoil", "token-match:efoil"), "E-Foils"))
        assertTrue(Matchers.extrasNameMatch(setOf("efoil", "token-match:efoil"), "Efoil Flite Board"))
        assertTrue(Matchers.extrasNameMatch(setOf("efoil", "token-match:efoil"), "eFoil Fliteboard"))
        assertTrue(Matchers.extrasNameMatch(setOf("efoil", "token-match:efoil"), "eFoil Fliteboard"))
    }

    @Test
    fun `kitchen utensils`() {
        assertFalse(Matchers.extrasNameMatch(setOf("kitchen utensils"), "Kitchen utensils (Galley equipment, cutlery)\""))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:kitchen utensils"), "Kitchen utensils (Galley equipment, cutlery)\""))
    }

    @Test
    fun `Dinghy`() {
        assertTrue(Matchers.extrasNameMatch(setOf("dinghy","dinghy outboard engine","outboard engine"), "Dinghy with outboard engine"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:dinghy"), "Dinghy with outboard engine"))
    }

    @Test
    fun `Gas cooker`() {
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:cooker"), "Gas cookers"))
    }

    @Test
    fun `Ice maker`() {
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:ice maker"), "Fresh juice maker"))
        assertFalse(Matchers.extrasNameMatch(setOf("ice maker"), "Fresh juice maker"))
    }

    @Test
    fun `Oven`() {
        assertFalse(Matchers.extrasNameMatch(setOf("oven"), "Convection oven"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:oven"), "Convection oven"))
    }

    @Test
    fun `Pillows`() {
        assertFalse(Matchers.extrasNameMatch(setOf("pillows"), "Extra pillows"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:pillows"), "Extra pillows"))
    }

    @Test
    fun `Autopilot`() {
        assertTrue(Matchers.extrasNameMatch(setOf("autopilot", "not:no autopilot"), "Autopilot"))
        assertFalse(Matchers.extrasNameMatch(setOf("autopilot", "not:no autopilot"), "No autopilot"))
    }

    @Test
    fun `Shower`() {
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:shower", "not:no inside shower"), "Bow shower"))
        assertTrue(Matchers.extrasNameMatch(setOf("token-match:shower", "not:no inside shower"), "Cockpit/stern, outside shower"))
        assertFalse(Matchers.extrasNameMatch(setOf("token-match:shower", "not:no inside shower"), "No inside shower"))
    }

}
