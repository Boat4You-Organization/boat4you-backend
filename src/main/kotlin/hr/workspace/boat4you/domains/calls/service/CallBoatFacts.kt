package hr.workspace.boat4you.domains.calls.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.capacity.BoatFactsText
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityColumns
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.utils.GuletRules
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `boat_facts`: what Nika may say about the boat of the inquiry (Mario 12.11.2026: clients ask about cabins, bathrooms,
 * basic equipment and where the boat is). A few short lines for a voice prompt, empty when the inquiry names no boat.
 *
 * Only our own columns and our own words reach it: the capacity line is the one IRIS gets ([BoatFactsText], from the
 * public capacity block), equipment is named by our catalogue code and never by the partner's text or comment, and the
 * agency, the owner and the partner's notes are never read. A gulet is always crewed ([GuletRules]). No distances and no
 * airport: the data has none, and a guess is worse than "our booking manager confirms it by e-mail".
 */
@Component
class CallBoatFacts(
    private val capacityMapper: YachtCapacityMapper,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Never fails the call: a boat that cannot be described is a call without boat facts. */
    fun describe(yacht: Yacht?): String {
        yacht ?: return ""
        return try {
            lines(yacht).joinToString("\n")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("boat facts for yacht ${yacht.id} failed, the call goes without them: ${e.message}")
            ""
        }
    }

    private fun lines(yacht: Yacht): List<String> {
        val gulet = GuletRules.isGulet(yacht.vesselType, yacht.model?.name)
        val charter = GuletRules.publicCharterTypes(yacht.yachtCharterTypes.mapNotNull { it.type }.toSet(), gulet)
        val json =
            objectMapper.createObjectNode().apply {
                set<JsonNode>(
                    "capacity",
                    objectMapper.valueToTree(capacityMapper.capacity(CapacityColumns.of(yacht), YachtCapacityMapper.Mode.BRIEF)),
                )
                putArray("charterType").apply { charter.forEach { add(it.name) } }
            }
        return listOfNotNull(
            boatLine(yacht, gulet)?.let { "Boat: $it" },
            BoatFactsText.capacityFacts(json),
            charterText(charter, gulet)?.let { "Charter: $it" },
            equipment(yacht).takeIf { it.isNotEmpty() }?.let { "Equipment listed for this boat: ${it.joinToString(", ")}" },
            base(yacht.location)?.let { "Base: $it" },
            times(yacht),
        )
    }

    /** "catamaran, 13.99 m (46 ft), built 2021", unknown parts left out. */
    private fun boatLine(
        yacht: Yacht,
        gulet: Boolean,
    ): String? {
        val type = if (gulet) "gulet" else yacht.vesselType?.takeIf { it != VesselType.OTHER }?.name?.lowercase()?.replace('_', ' ')
        val length =
            (yacht.length ?: yacht.model?.length)?.takeIf { it.signum() > 0 }?.let { m ->
                val feet = m.multiply(FEET_PER_METRE).setScale(0, RoundingMode.HALF_UP)
                "${m.stripTrailingZeros().toPlainString()} m ($feet ft)"
            }
        val year = yacht.buildYear?.takeIf { it > 0 }?.let { "built $it" }
        return listOfNotNull(type, length, year).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun charterText(
        types: Set<CharterType>,
        gulet: Boolean,
    ): String? {
        if (gulet) return BoatFactsText.GULET_CHARTER
        val crewed = types.any { it.name in BoatFactsText.CREWED_CHARTER_TYPES }
        val bareboat = CharterType.BAREBOAT in types
        return when {
            crewed && bareboat -> "bareboat or crewed"
            crewed -> "crewed"
            bareboat -> "bareboat"
            else -> null
        }
    }

    /** The things a client asks about on the phone, in [ASKED_ABOUT] order, each said once. */
    private fun equipment(yacht: Yacht): List<String> {
        val codes = yacht.yachtEquipments.mapNotNull { it.equipment?.labelCode }.toSet()
        return ASKED_ABOUT.filter { (code, _) -> code in codes }.map { it.second }.distinct().take(EQUIPMENT_MAX)
    }

    /** "Marina Baotić, Trogir, Croatia": the marina, its town when the name does not already say it, the country. */
    private fun base(location: Location?): String? {
        val name = location?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val city = location.city?.trim()?.takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
        val country = location.country?.name?.trim()?.takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
        return listOfNotNull(name, city, country).joinToString(", ")
    }

    private fun times(yacht: Yacht): String? {
        val checkIn = yacht.defaultCheckin?.trim()?.takeIf { TIME.matches(it) }?.let { "check-in from $it" }
        val checkOut = yacht.defaultCheckout?.trim()?.takeIf { TIME.matches(it) }?.let { "check-out by $it" }
        return listOfNotNull(checkIn, checkOut).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Usual times: $it" }
    }

    private companion object {
        val FEET_PER_METRE = BigDecimal("3.28084")
        const val EQUIPMENT_MAX = 16
        val TIME = Regex("""^\d{1,2}[:.]\d{2}$""")

        /** Catalogue label code to the spoken word; the old alias codes say the same word as the code that replaced them. */
        val ASKED_ABOUT =
            listOf(
                "fridge" to "fridge",
                "refrigerator" to "fridge",
                "freezer" to "freezer",
                "air-conditioning" to "air conditioning",
                "heating" to "heating",
                "generator" to "generator",
                "water-maker" to "watermaker",
                "solar-panels" to "solar panels",
                "inverter" to "inverter",
                "dinghy" to "dinghy",
                "outboard-engine" to "outboard engine",
                "autopilot" to "autopilot",
                "bow-thruster" to "bow thruster",
                "bow-thruster-deck" to "bow thruster",
                "outside-GPS-plotter" to "chart plotter",
                "electric-winches" to "electric winches",
                "electric-toilet" to "electric toilets",
                "wifi" to "wifi",
                "bimini" to "bimini",
                "ice-maker" to "ice maker",
                "dishwasher" to "dishwasher",
                "washing-machine" to "washing machine",
                "microwave" to "microwave",
                "coffee-machine" to "coffee machine",
                "BBQ" to "barbecue",
                "stand-up-paddle" to "stand-up paddle board",
            )
    }
}
