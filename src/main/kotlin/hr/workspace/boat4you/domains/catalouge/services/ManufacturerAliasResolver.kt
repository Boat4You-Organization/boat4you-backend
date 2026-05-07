package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.stereotype.Component

/**
 * Resolves partner-supplied manufacturer names to a single canonical brand
 * name so MMK + NauSys (or any other source) cannot create parallel records
 * for the same boat-builder under slightly different naming conventions.
 *
 * Background: Lagoon catamarans appear as "Lagoon" in NauSys but
 * "Lagoon-Bénéteau" in MMK because the brand sits inside Bénéteau Group post
 * 2014 acquisition. Without normalisation we end up with two manufacturer
 * rows after every sync — Mario rule (May 2026): "oba moraju biti pod Lagoon".
 *
 * Add new aliases here as they surface. Lookup is case- and accent-aware
 * (lowercase comparison after Unicode-NFD normalisation strips diacritics)
 * so any partner spelling lands on the same canonical name.
 */
@Component
class ManufacturerAliasResolver {

    /**
     * Map of normalised partner-supplied names (lowercase, accent-stripped)
     * → canonical brand name we persist in `manufacturer.name`.
     */
    private val aliases: Map<String, String> = mapOf(
        // Brand-merge aliases (Lagoon-Bénéteau group, Bali post Catana
        // acquisition). Without these the sync forks the brand again.
        "lagoon-beneteau" to "Lagoon",
        "lagoon beneteau" to "Lagoon",
        "catana group" to "Bali",
        "catana" to "Bali",
        // Mass dedup aliases — 27 groups merged 4.5.2026. Each entry maps a
        // partner-supplied variant to the canonical name (the row that
        // survived the merge based on highest yacht-count). Without these,
        // every full sync would re-create the deleted row.
        "aicon yachts" to "Aicon",
        "alu yachts" to "Alu Marine",
        "alubat" to "Alubat Yachts",
        "armor boats" to "Armor Yachts",
        "atlantic marine" to "Atlantic",
        "barracuda yachts" to "Barracuda",
        "beneteau" to "Bénéteau",
        "bluegame yachts" to "Bluegame",
        "elan" to "Elan Marine",
        "feeling" to "Feeling Yachts",
        "filippetti yacht" to "Filippetti Yachts",
        "four winns" to "Four Winns Boats",
        "island spirit" to "Island Spirit Yachts",
        "leopard yachts" to "Leopard",
        "luna" to "Luna Catamarans",
        "marquis" to "Marquis Yachts",
        "nautiner yachts" to "Nautiner Yacht",
        "nimbus" to "Nimbus Group",
        "nuova jolly marine" to "Nuova Jolly",
        "pershing yachts" to "Pershing",
        "prestige yachts" to "Prestige",
        "rio yachts" to "Rio Boats",
        "starfisher" to "Starfisher Yachts",
        "vandutch" to "VanDutch",
        "viko yachts" to "Viko",
        "voyage yachts" to "Voyage Catamaran",
        "windy yachts" to "Windy Boats",
    )

    /**
     * Returns the canonical brand name for a partner-supplied raw name.
     * If no alias matches, returns the input trimmed (no other transform).
     * Never returns blank when input is blank — caller decides how to handle.
     */
    fun canonicalName(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return trimmed
        val key = normalise(trimmed)
        return aliases[key] ?: trimmed
    }

    private fun normalise(input: String): String =
        java.text.Normalizer
            .normalize(input, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
}
