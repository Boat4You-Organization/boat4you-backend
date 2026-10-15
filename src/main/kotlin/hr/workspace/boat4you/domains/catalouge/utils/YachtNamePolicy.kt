package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import org.slf4j.LoggerFactory

/**
 * R02 (owner rule 14.10.2026, „Di piše NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo"): the public name of a partner yacht.
 * `yacht.name` holds this public name; the raw partner name is kept in the INTERNAL column `yacht.partner_name`
 * (V9_85, never in a public DTO). The MMK and NauSys yacht syncs apply it on every write, V9_87 applied it once to the
 * existing rows - `r02_yacht_public_name_v1` there is the SQL twin of [publicName] (R02NamePolicyParityTest).
 *
 * 1. A whole name that is a curated agency trade name (`partner/yacht_name_brand.txt`, flag F4) -> "Boat4You".
 * 2. A charter-brand token inside a fleet label -> "Boat4You" (flag F1 = literal): "Sunsail 424/4/4 Premium" ->
 *    "Boat4You 424/4/4 Premium", "Sunsail/Moorings 41.3" -> "Boat4You 41.3", "Moorings Oceanis 30.1 Club" ->
 *    "Boat4You Oceanis 30.1 Club". "Moorings" counts as the brand only before a number or as the first word.
 * 3. Anything else is returned unchanged (case untouched - title case stays display-only).
 *
 * Idempotent, never blank for a non-blank input. Matching (twins, successors) keeps reading the raw name through
 * `COALESCE(partner_name, name)`.
 */
object YachtNamePolicy {
    const val PUBLIC_BRAND = "Boat4You"
    const val BRAND_NAMES_RESOURCE = "partner/yacht_name_brand.txt"

    // ALL-CAPS partner spellings too (the owner wrote "SUNSAIL"; NauSys sends capitals), like R01 replace.txt; "Navigare"
    // only as a fleet label (before a number or as "Navigare Yachting") - a boat named just "Navigare" (Italian) stays.
    private val MOORINGS_FIRST_WORD = Regex("^(Moorings|MOORINGS)\\b")
    private val FLEET_TOKEN =
        Regex("\\b(NSS Charter|NSS CHARTER|NSS|Sunsail|SUNSAIL|The Moorings|THE MOORINGS|(?:Moorings|MOORINGS)(?=\\s*[0-9])|Navigare Yachting|NAVIGARE YACHTING|(?:Navigare|NAVIGARE)(?=\\s*[0-9]))\\b")
    private val REPEATED_BRAND = Regex("$PUBLIC_BRAND([\\s/]+$PUBLIC_BRAND)+")
    private val SPACES = Regex("\\s{2,}")
    private val WHITESPACE = Regex("\\s+")

    // Same characters as V9_67 location_name_key / V9_87, so the Kotlin and SQL folds agree byte for byte.
    private const val ACCENTED = "šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ"
    private const val PLAIN = "szccdaaaaaaeeeeiiiiooooouuuucn"

    private val log = LoggerFactory.getLogger(YachtNamePolicy::class.java)

    /** Folded curated agency trade names (rule 1). */
    val brandNames: Set<String> by lazy { loadBrandNames() }

    fun publicName(raw: String): String =
        when {
            raw.isBlank() -> raw
            fold(raw) in brandNames -> PUBLIC_BRAND
            !raw.contains(FLEET_TOKEN) && !raw.contains(MOORINGS_FIRST_WORD) -> raw
            else ->
                raw
                    .replace(MOORINGS_FIRST_WORD, PUBLIC_BRAND)
                    .replace(FLEET_TOKEN, PUBLIC_BRAND)
                    .replace(REPEATED_BRAND, PUBLIC_BRAND)
                    .replace(SPACES, " ")
                    .trim(' ') // spaces only, as PostgreSQL btrim (V9_87 parity)
        }

    /**
     * The MMK / NauSys yacht syncs, on every write: yacht.name = the public name, yacht.partner_name = the raw partner name
     * (otherwise a sync would put "Sunsail ..." back after V9_87). A boat named after its own agency but not on the curated
     * list keeps its name and is logged once for review (owner flag F4). [source] names the partner record for the log.
     */
    fun applyTo(
        yacht: Yacht,
        raw: String?,
        agency: Agency?,
        source: String,
    ) {
        if (raw != null && yacht.partnerName != raw && namedAfterOwnAgency(raw, agency?.name)) {
            log.info("R02 boat named after its agency id=${yacht.id} $source agencyId=${agency?.id}")
        }
        yacht.name = raw?.let { publicName(it) }
        yacht.partnerName = raw
    }

    /** The raw name is its own agency's name but not on the curated list: logged for review (flag F4), never renamed. */
    fun namedAfterOwnAgency(
        raw: String?,
        ownAgencyName: String?,
    ): Boolean = !raw.isNullOrBlank() && !ownAgencyName.isNullOrBlank() && fold(raw) == fold(ownAgencyName) && fold(raw) !in brandNames

    /**
     * Name for the trip hub, push and chat texts (flag F11): the public name, or the model label when the public name
     * is exactly "Boat4You" (a boat named after its agency) - "Welcome aboard Lagoon 47!", not "Welcome aboard Boat4You!".
     * Null when neither is known (callers say "your yacht").
     */
    fun tripName(
        publicName: String?,
        modelName: String?,
    ): String? {
        val name = publicName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && !isBrandOnly(name)) return name
        return modelName?.trim()?.takeIf { it.isNotEmpty() } ?: name
    }

    fun isBrandOnly(name: String?): Boolean = name?.trim().equals(PUBLIC_BRAND, ignoreCase = true)

    /** lowercase, the V9_67 accents folded, whitespace collapsed, trimmed. */
    fun fold(value: String): String {
        val lower = value.lowercase()
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            val i = ACCENTED.indexOf(c)
            sb.append(if (i >= 0) PLAIN[i] else c)
        }
        return sb.toString().replace(WHITESPACE, " ").trim(' ')
    }

    private fun loadBrandNames(): Set<String> {
        val stream =
            YachtNamePolicy::class.java.classLoader.getResourceAsStream(BRAND_NAMES_RESOURCE)
                ?: error("$BRAND_NAMES_RESOURCE missing from the classpath")
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { fold(it) }.toSet()
        }
    }
}
