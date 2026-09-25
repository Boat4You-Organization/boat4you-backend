package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 25.9.2026: sea charter only. The positive names are the real manufacturers / MMK companies of the river operators
 * found live on the sites (and the ones blacklisted by hand earlier); the negatives are sea builders and sea charter
 * companies that must never be caught.
 */
class InlandVesselRulesTests {
    @Test
    fun `real inland builders are caught`() {
        listOf(
            "Le Boat",
            "LeBoat",
            "Nicols",
            "Nicols Yacht",
            "Pénichette",
            "Penichette",
            "Locaboat",
            "Kuhnle",
            "Kormoran",
            "De Drait",
            "Houseboat Holidays Italia S.R.L.",
            "House boat",
            "Hausboot",
            "Nautilus Hausboote",
            "Linssen",
            "Linssen Yachts",
            "Gruno",
            "Gruno Motoryachten",
            "Pedro Boat",
            "Triton Boats",
            "Brandaris",
            "Veha Motorjachten",
            "  LE BOAT ",
            // model names of models whose manufacturer we never resolved (Kuhnle-Tours, De Drait)
            "Kormoran 1140",
            "Pedro Skiron 35 ",
            "De Drait Vri-Jon Contessa 1370",
            "Nicols Estivale Octo",
            "Linssen Grand Sturdy 35.0 AC",
        ).filterNot { InlandVesselRules.isInlandBuilder(it) }.shouldBeEmpty()
    }

    @Test
    fun `sea builders are not caught`() {
        listOf(
            "Beneteau",
            "Lagoon",
            "Bali",
            "Fountaine Pajot",
            "Jeanneau",
            "Bavaria",
            "Dufour",
            "Northman",
            "Balt Yachts",
            "Delphia Yachts",
            "Cobra Yachts",
            "Karnic",
            "Bayliner",
            "Delos",
            "Aquariva",
            // look-alikes: "Triton" alone can be a sea model, "Pilot House" is a sea deck style
            "Triton",
            "Pilot House",
            "Archambault Boats",
            "J/Boats",
            "Rio Boats",
            "Riviera",
            "Four Winns Boats",
            "Leopard",
            // sea model names
            "Triton 48 - 4 + 1 cab.",
            "Bavaria 40 Vision",
            "Horizon 48",
            "Northman 1200 Elegance",
            "Futura 40 Grand Horizon",
            "Lagoon 42",
        ).filter { InlandVesselRules.isInlandBuilder(it) }.shouldBeEmpty()
    }

    @Test
    fun `real river operators are caught by name`() {
        listOf(
            "Le Boat",
            "LeBoat",
            "Riverly",
            "Canal Evasion",
            "Houseboat Holidays Italia",
            "Locaboat Holidays",
            "Kuhnle-Tours",
            "Gota Kanal Charter",
            "Canal Boats Telemark",
            "Nicols",
            "Navigation Fluviale",
            "Péniche Cyrano",
            "River Cruises",
            "Boating Holidays",
            "Hausboot Charter",
        ).filterNot { InlandVesselRules.isRiverOperator(it) }.shouldBeEmpty()
    }

    @Test
    fun `sea charter companies are not caught by name`() {
        listOf(
            "Navigare Yachting",
            "Navigo",
            "Sunsail",
            "The Moorings",
            "Dream Yacht Charter",
            "Blue Wave",
            "Aquariva",
            "Starsails Yachtcharter",
            "Delos",
            "Riviera Charter",
            "Canalis Yachting",
        ).filter { InlandVesselRules.isRiverOperator(it) }.shouldBeEmpty()
    }

    @Test
    fun `operators with a neutral name are caught through their fleet's builders`() {
        // Anjou Navigation, SBS Fleesensee, Revier Charter, Jachtwerf Oost, Hibo Yachtcharter, Blue Wave Yachting by
        // Delos, Aqua Libra, 3Lacs Yacht Charter: the name says nothing, the boats do.
        listOf("Nicols Yacht", "De Drait", "Gruno", "Brandaris", "Linssen", "Pedro Boat", "Veha Motorjachten")
            .all { InlandVesselRules.isInlandBuilder(it) } shouldBe true
    }

    @Test
    fun `null and blank are not caught`() {
        InlandVesselRules.isInlandBuilder(null) shouldBe false
        InlandVesselRules.isInlandBuilder("  ") shouldBe false
        InlandVesselRules.isRiverOperator(null) shouldBe false
        InlandVesselRules.isRiverOperator("") shouldBe false
    }
}
