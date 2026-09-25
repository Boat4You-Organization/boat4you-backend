package hr.workspace.boat4you.domains.catalouge.utils

import java.text.Normalizer

/**
 * boat4you and the six sister sites sell SEA charter only (Mario, standing rule): river, canal and lake cruisers -
 * penichettes, houseboats, the Dutch/German inland motor cruisers - must never be listed. VesselType cannot catch them
 * (their operators list them as MOTORBOAT / MOTOR_YACHT), and since the MMK agency mirror auto-creates every unknown
 * company as an active agency (5.7.2026) Le Boat (901 boats), Riverly, Canal Evasion and ten more river operators came
 * in that way, e.g. Le Boat "Caprice Comfort 51" on www.boat4you.com (all 13 switched off by hand 25.9.2026, V9_63).
 *
 * - [isInlandBuilder]: the yacht's manufacturer - or its model name, which starts with the builder ("Kormoran 1140",
 *   for models whose manufacturer we never resolved) - is an inland-only builder -> the MMK and NauSys yacht sync skip
 *   the yacht and switch off one imported earlier; the weekly inventory uses the same rule.
 * - [isRiverOperator]: a NEW partner company's name reads like a river operator -> the agency mirror creates it
 *   inactive (a manual OFF, so the mirror never re-activates it). Operators whose name gives nothing away (Anjou
 *   Navigation, Aqua Libra, ...) are still caught boat by boat through their builders.
 *
 * Both match whole words, ignoring case and accents. Add a pattern to cover another builder / operator; keep sea names
 * out: only the builder "Triton Boats" is inland ("Triton" alone can be a sea model), "Delos" is a sea brand.
 */
object InlandVesselRules {
    /** Manufacturers / shipyards that build only river, canal and lake cruisers; also matched against model names. */
    val INLAND_BUILDERS: List<Regex> =
        listOf(
            """\ble[\s-]*boat\b""", // Le Boat, LeBoat
            """\bnicols\b""", // Nicols, Nicols Yacht
            """\bpenichette\b""",
            """\bloca[\s-]*boat\b""",
            """\bkuhnle\b""",
            """\bkormoran\b""",
            """\bde[\s-]*drait\b""",
            """\bhouse[\s-]*boat""", // houseboat(s), house boat, Houseboat Holidays Italia S.R.L.
            """\bhaus[\s-]*boot""", // Hausboot, Hausboote
            """\blinssen\b""",
            """\bgruno\b""",
            """\bpedro\b""", // Pedro Boat, model "Pedro Skiron 35"
            """\btriton[\s-]*boats?\b""",
            """\bbrandaris\b""",
            """\bveha\b""", // Veha Motorjachten
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Words in a partner company name that mark a river / canal operator. */
    val RIVER_OPERATORS: List<Regex> =
        listOf(
            """\ble[\s-]*boat\b""", // Le Boat, LeBoat
            """\bloca[\s-]*boat\b""",
            """\briverly\b""",
            """\bnicols\b""",
            """\bkuhnle\b""", // Kuhnle-Tours
            """\bhouse[\s-]*boat""",
            """\bhaus[\s-]*boot""",
            """\b[ck]anal(s|e|en)?\b""", // Canal Evasion, Gota Kanal Charter, Canal Boats Telemark
            """\bfluvia""", // fluvial, fluviale, fluviaux
            """\bpeniche""", // peniche(s), penichette
            """\brivers?\b""",
            """\bboating[\s-]+holidays?\b""",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val accents = Regex("""\p{Mn}+""")

    fun isInlandBuilder(manufacturerName: String?): Boolean = matchesAny(manufacturerName, INLAND_BUILDERS)

    fun isRiverOperator(companyName: String?): Boolean = matchesAny(companyName, RIVER_OPERATORS)

    private fun matchesAny(
        name: String?,
        patterns: List<Regex>,
    ): Boolean {
        if (name.isNullOrBlank()) return false
        val plain = accents.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
        return patterns.any { it.containsMatchIn(plain) }
    }
}
