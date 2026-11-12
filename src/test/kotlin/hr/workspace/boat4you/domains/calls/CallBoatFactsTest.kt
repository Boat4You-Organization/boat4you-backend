package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.service.CallBoatFacts
import hr.workspace.boat4you.domains.calls.service.CallContextBuilder
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtCharterType
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.Instant

/**
 * `boat_facts`, what Nika may say about the boat (Mario 12.11.2026): cabins, bathrooms, the equipment clients ask about
 * and the base. Never the agency or owner, never partner text, a gulet always crewed, nothing for an inquiry without a boat.
 */
class CallBoatFactsTest {
    private val capacity = YachtCapacityMapper(CapacityFixtures.sanitizer())
    private val facts = CallBoatFacts(capacity, CapacityFixtures.mapper, mock(YachtRepository::class.java), mock(PlatformTransactionManager::class.java))

    private fun yacht(configure: Yacht.() -> Unit = {}) =
        Yacht().apply {
            id = 11
            name = "Anthea"
            partnerName = "Anthea (Odisej fleet)"
            agency = Agency().apply { name = "Odisej Ltd" }
            vesselType = VesselType.CATAMARAN
            length = BigDecimal("13.99")
            buildYear = 2021
            cabins = 4
            berths = 10
            cabinBerths = 8
            salonBerths = 2
            wc = 4
            maxPersons = 10
            cabinsNote = "4+2, ask Odisej Ltd"
            internalRemark = "owner Ivo Odisej, call the base"
            defaultCheckin = "17:00"
            defaultCheckout = "09:00"
            model =
                Model().apply {
                    name = "Lagoon 46"
                    manufacturer = Manufacturer().apply { name = "Lagoon" }
                }
            location =
                Location().apply {
                    name = "Marina Baotić"
                    city = "Trogir"
                    country = Country().apply { name = "Croatia" }
                }
            charter(CharterType.BAREBOAT)
            // The partner's own order and wording, with a comment that names the agency: only our code is read.
            equip("wifi", "generator", "air-conditioning", "refrigerator", "fridge", "life-jackets", "water-maker", "dinghy", "outboard-engine", "autopilot")
            configure()
        }

    private fun Yacht.charter(vararg types: CharterType) {
        yachtCharterTypes = types.map { YachtCharterType(this, it) }.toMutableSet()
    }

    private fun Yacht.equip(vararg codes: String) {
        yachtEquipments =
            codes
                .map { code ->
                    YachtEquipment().apply {
                        name = "$code by Odisej Ltd"
                        comment = "serviced by Odisej Ltd"
                        equipment = Equipment().apply { labelCode = code }
                    }
                }.toMutableSet()
    }

    @Test
    fun `a boat is described in a few short lines`() {
        facts.describe(yacht()) shouldBe
            """
            Boat: catamaran, 13.99 m (46 ft), built 2021
            Cabins: 4, berths: 10 (8 in cabins + 2 in the saloon), WC: 4, max people on board: 10
            Charter: bareboat
            Equipment listed for this boat: fridge, air conditioning, generator, watermaker, dinghy, outboard engine, autopilot, wifi
            Home base: Marina Baotić, Trogir, Croatia
            Usual times: check-in from 17:00, check-out by 09:00
            """.trimIndent()
    }

    @Test
    fun `an inquiry without a boat has no boat facts`() {
        facts.describe(null) shouldBe ""
        val inquiry = Inquiry().apply { id = 7 }
        val v = CallContextBuilder.variables(inquiry, call(), NOW, SETTINGS, boatFacts = facts.describe(inquiry.yacht))
        v.getValue("boat_facts") shouldBe ""
        CallContextBuilder.variables(inquiry, call(), NOW, SETTINGS).getValue("boat_facts") shouldBe ""
    }

    @Test
    fun `the agency, the owner and the partner's own text never reach the call`() {
        val inquiry =
            Inquiry().apply {
                id = 7
                yacht = yacht()
            }
        val v = CallContextBuilder.variables(inquiry, call(), NOW, SETTINGS, boatFacts = facts.describe(inquiry.yacht))
        v.getValue("boat_facts") shouldContain "Cabins: 4"
        val all = v.values.joinToString(" ")
        listOf("Odisej", "Ivo", "fleet", "serviced", "ask ").forEach { all shouldNotContain it }
    }

    @Test
    fun `a gulet is always crewed, whatever the partner tagged`() {
        val byType =
            yacht {
                vesselType = VesselType.GULET
                charter(CharterType.BAREBOAT)
                crewNumber = 4
            }
        val byModel =
            yacht {
                vesselType = VesselType.MOTOR_YACHT
                model = Model().apply { name = "Gulet" }
                charter()
            }
        listOf(byType, byModel).forEach { gulet ->
            val text = facts.describe(gulet)
            text shouldContain "Boat: gulet"
            text shouldContain "Charter: crewed - a gulet always sails with its crew"
            text shouldNotContain "Charter: bareboat"
        }
        facts.describe(byType) shouldContain "crew: 4"
    }

    @Test
    fun `equipment the partner marks as absent is not listed`() {
        val boat =
            yacht {
                equip("fridge", "air-conditioning", "generator", "dinghy")
                yachtEquipments.first { it.equipment?.labelCode == "air-conditioning" }.comment = "No"
                yachtEquipments.first { it.equipment?.labelCode == "generator" }.quantity = BigDecimal.ZERO
                defaultCheckin = "25:99"
            }
        val text = facts.describe(boat)
        text shouldContain "Equipment listed for this boat: fridge, dinghy\n"
        text shouldNotContain "check-in"
    }

    @Test
    fun `crewed and mixed boats say so, an untagged boat says nothing about the charter`() {
        facts.describe(yacht { charter(CharterType.CREWED) }) shouldContain "Charter: crewed"
        facts.describe(yacht { charter(CharterType.BAREBOAT, CharterType.ALL_INCLUSIVE) }) shouldContain "Charter: bareboat or crewed"
        facts.describe(yacht { charter(CharterType.UNKNOWN) }) shouldNotContain "Charter:"
    }

    @Test
    fun `unknown parts are left out and the text stays short enough to be spoken from`() {
        val bare =
            Yacht().apply {
                name = "Luna"
                vesselType = VesselType.SAILING_YACHT
                cabins = 3
                defaultCheckin = ""
                defaultCheckout = "see contract of Odisej Ltd"
                location = Location().apply { name = "Trogir, Marina Trogir" }
            }
        facts.describe(bare) shouldBe "Boat: sailing yacht\nCabins: 3\nHome base: Trogir, Marina Trogir"
        // The model's length stands in for a boat without its own; a town already in the marina's name is said once.
        val fromModel =
            yacht {
                length = null
                model = Model().apply { length = BigDecimal("11.90") }
                location =
                    Location().apply {
                        name = "Trogir, Marina Trogir"
                        city = "Trogir"
                    }
            }
        facts.describe(fromModel) shouldContain "11.9 m (39 ft)"
        facts.describe(fromModel) shouldContain "Home base: Trogir, Marina Trogir\n"
        val everything = yacht { equip(*ALL_CODES) }
        facts.describe(everything).length shouldBeLessThan 700
    }

    private fun call() =
        InquiryCall().apply {
            kind = CallKind.CONFIRMATION
            language = "en"
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-06T10:00:00Z")
        val SETTINGS = CallsSettings.Snapshot(transferNumber = "+385981234567")
        val ALL_CODES =
            (
                "fridge freezer air-conditioning heating generator water-maker solar-panels inverter dinghy outboard-engine autopilot " +
                    "bow-thruster outside-GPS-plotter electric-winches electric-toilet wifi bimini ice-maker dishwasher washing-machine " +
                    "microwave coffee-machine BBQ stand-up-paddle"
            ).split(" ").toTypedArray()
    }
}
