package hr.workspace.boat4you.domains.catalouge.utils

import java.text.Normalizer

/**
 * One-way fees are never obligatory on a round trip (Mario 16.11.2026: "ne smiju biti ovi one way kao obligatory").
 *
 * Partners list the fee for every drop-off route as an obligatory extra of the boat ("One way fee Split-Marina Frapa
 * Rogoznica", "One Way - Porto - Lisbon (L)", "Göcek Marina Fee (One-Way)"), with no base or route attached, and often
 * attach it to round-trip offers as well. A week from Rogoznica back to Rogoznica then listed four one-way fees
 * (7,850 EUR) as payable at the base. On 16.11.2026: 2,844 obligatory boat rows on ~905 boats, 2,067 obligatory rows on
 * round-trip offers.
 *
 * Rules:
 * - an offer charges its own one-way fee only when it really ends in another marina (Offer.chargeableOfferExtras);
 * - a one-way fee listed on the boat itself is never a service of the boat (boat page, e-mails, charter facts). On a
 *   one-way offer that carries no fee of its own, the price takes the boat's fee for that route ([forRoute]).
 * A transfer or taxi priced per direction ("Airport Transfer 4 pax (one way)", "One way transfer Mykonos") and the APA
 * are services, not one-way fees.
 */
object OneWayFeeRules {
    private const val ONE_WAY = """one\s*[-–‑]?\s*way"""

    /** The name starts with "One way" / "One-Way" / "Oneway". */
    private val STARTS_ONE_WAY = Regex("""^\s*$ONE_WAY\b""", RegexOption.IGNORE_CASE)

    /** "… (One-Way)": a fee only when the name says so ("Göcek Marina Fee (One-Way)", not "Taxi (one way)"). */
    private val BRACKET_ONE_WAY = Regex("""\(\s*$ONE_WAY\s*\)""", RegexOption.IGNORE_CASE)

    private val FEE_WORDS = Regex("""fee|charge|supplement|delivery|charter""", RegexOption.IGNORE_CASE)
    private val SERVICE_WORDS = Regex("""transfer|pick\s*-?\s*up|shuttle|taxi""", RegexOption.IGNORE_CASE)

    fun isOneWayFee(name: String?): Boolean {
        if (name == null) return false
        val feeWords = FEE_WORDS.containsMatchIn(name)
        return when {
            STARTS_ONE_WAY.containsMatchIn(name) -> feeWords || !SERVICE_WORDS.containsMatchIn(name)
            BRACKET_ONE_WAY.containsMatchIn(name) -> feeWords
            else -> false
        }
    }

    /** Words of a marina's name that say nothing about the place ("ACI Marina Split" -> "split"). */
    private val GENERIC =
        setOf("marina", "marine", "port", "porto", "puerto", "base", "harbour", "harbor", "yacht", "yachts", "club", "town", "city", "bay")

    private val NON_LETTERS = Regex("""[^\p{L}]+""")

    private fun plainWords(text: String?): Set<String> =
        Normalizer
            .normalize(text ?: "", Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .split(NON_LETTERS)
            .toSet()

    /**
     * The boat's one-way fees that belong to a one-way charter whose far end (the marina that is not the boat's base) is
     * named by [farEnd] (marina name and city): the fees naming that place, else the only fee there is, else none.
     */
    fun <T> forRoute(
        fees: List<T>,
        name: (T) -> String?,
        farEnd: List<String?>,
    ): List<T> {
        val place = farEnd.flatMap { plainWords(it) }.filter { it.length >= 4 && it !in GENERIC }.toSet()
        val named = fees.filter { fee -> plainWords(name(fee)).any { it in place } }
        return when {
            named.isNotEmpty() -> named
            fees.size == 1 -> fees
            else -> emptyList()
        }
    }
}
