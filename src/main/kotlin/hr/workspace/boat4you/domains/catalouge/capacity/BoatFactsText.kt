package hr.workspace.boat4you.domains.catalouge.capacity

import com.fasterxml.jackson.databind.JsonNode

/**
 * How a boat's capacity and charter are put into words for an AI, from the public yacht JSON. One wording for the IRIS
 * chat (the CURRENT PAGE block, the search rows) and for the phone agent Nika (`boat_facts`), so the two never describe
 * the same boat differently.
 */
object BoatFactsText {
    /** Charter types whose listing may state a crew count (capacity contract v1, 7.2). */
    val CREWED_CHARTER_TYPES = setOf("CREWED", "ALL_INCLUSIVE", "CRUISE")

    /** What the model is told about every gulet (GuletRules: a gulet is never bareboat, Mario 9.10.2026). */
    const val GULET_CHARTER = "crewed - a gulet always sails with its crew, never bareboat or skipper-only"

    /**
     * "Cabins: 6 (crew cabins: 2), berths: 13 (12 + 1 crew), WC: 6, max people on board: 14 (recommended 10), crew: 1"
     * from the detail JSON's `capacity` block (capacity contract v1, 2.6), unknown parts left out. Berths are berths,
     * never "sleeps up to {maxPersons}"; crew only when a crewed charter is offered. No crew WC: until the corrected
     * NauSys sync (cusma3, nightly) has rewritten a boat, its stored crew WC is still its guest WC, and the chat goes
     * live with cusma2, ahead of that sync - the boat page shows the crew WC once the sync has run.
     */
    fun capacityFacts(y: JsonNode): String? {
        val c = y.path("capacity")
        val parts = mutableListOf<String>()
        dimText(c.path("cabins"), y.path("cabins"))?.let { cabins ->
            val crewCabins = positive(c.path("crewCabins"))
            parts += "Cabins: $cabins" + (crewCabins?.let { " (crew cabins: $it)" } ?: "")
        }
        dimText(c.path("berths"), y.path("berths"))?.let { parts += "berths: $it" }
        dimText(c.path("heads"), y.path("wc"))?.let { parts += "WC: $it" }
        positive(c.path("showers"))?.let { parts += "showers: $it" }
        // flat fields only for a payload without the block (an older API answer)
        val max = positive(c.path("maxPersons")) ?: if (!c.isObject) positive(y.path("maxPersons")) else null
        val recommended = positive(c.path("recommendedPersons"))
        when {
            max != null -> parts += "max people on board: $max" + (recommended?.takeIf { it != max }?.let { " (recommended $it)" } ?: "")
            recommended != null -> parts += "recommended people: $recommended"
        }
        val crewed = y.path("charterType").any { it.asText() in CREWED_CHARTER_TYPES }
        val crew = positive(c.path("crewNumber")) ?: if (!c.isObject) positive(y.path("crewNumber")) else null
        if (crewed && crew != null) parts += "crew: $crew"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** "13 (12 + 1 crew)" / "12 (10 in cabins + 2 crew)" / "6", or null when the figure is unknown. */
    private fun dimText(
        dim: JsonNode,
        flat: JsonNode,
    ): String? {
        val value = positive(dim.path("value")) ?: if (dim.isMissingNode) positive(flat) else null
        value ?: return null
        val split = dim.path("split")
        val parts =
            listOfNotNull(
                positive(split.path("guests"))?.toString(),
                positive(split.path("inCabins"))?.let { "$it in cabins" },
                positive(split.path("saloon"))?.let { "$it in the saloon" },
                positive(split.path("crew"))?.let { "$it crew" },
                positive(split.path("skipper"))?.let { "$it skipper" },
            )
        return if (parts.size >= 2) "$value (${parts.joinToString(" + ")})" else value.toString()
    }

    private fun positive(node: JsonNode): Int? = node.takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
}
