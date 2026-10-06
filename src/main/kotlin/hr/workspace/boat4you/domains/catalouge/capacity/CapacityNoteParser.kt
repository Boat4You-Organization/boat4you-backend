package hr.workspace.boat4you.domains.catalouge.capacity

/** The three capacity figures a partner may annotate with a note. */
enum class CapacityDim {
    CABINS,
    BERTHS,
    HEADS,
}

/** Parts of a capacity figure that add up exactly to it; null = no such part. */
data class CapacitySplit(
    val guests: Int? = null,
    val inCabins: Int? = null,
    val saloon: Int? = null,
    val crew: Int? = null,
    val skipper: Int? = null,
)

/**
 * Strict reading of an MMK capacity note (capacity contract v1, 5.2) and the NauSys berths split. Read time only, never
 * stored: the note itself is stored as sent, so a rule change here needs no re-sync. The input is the SANITIZED note -
 * a hidden note gives no split.
 *
 *  1. lower-case; strip one enclosing "( ... )"; a note starting with + - – / or "plus", or containing "/", -> null
 *     (the partner's number may exclude the extra: "4 [+2]", "4 [/5]");
 *  2. split on "+": at least 2 parts, each `<1-3 digits> <optional label of letters, blanks, apostrophes>`;
 *  3. first part: label empty or a guest word (pax, guests, for (the) guests, people, persons) -> guests;
 *  4. every other part MUST carry a role label, optionally followed by the figure's own noun (cabin / berth / wc):
 *     crew (crew, hostess, for (the) crew / hostess), skipper (skipper(s), skipper's, for (the) skipper - never folded
 *     into crew), saloon (saloon, salon, in (the) saloon / salon - berths only). An unlabelled extra ("8+2": Jangada's
 *     2 is the convertible saloon table) or any other label (double, bunk, forepeak, bow, ...) -> null;
 *  5. the parts must add up EXACTLY to the partner's number, else null (Dione II cabins "5 double +1 ... + 1" = 7 != 6).
 */
object CapacityNoteParser {
    private val GUEST_LABELS = setOf("", "pax", "guest", "guests", "for guests", "for the guests", "people", "persons")
    private val CREW_ROLES = setOf("crew", "for crew", "for the crew", "hostess", "for hostess", "for the hostess")
    private val SKIPPER_ROLES = setOf("skipper", "skippers", "skipper's", "for skipper", "for the skipper")
    private val SALOON_ROLES = setOf("saloon", "salon", "in saloon", "in the saloon", "in salon", "in the salon")
    private val DIM_NOUN =
        mapOf(
            CapacityDim.CABINS to Regex("\\s*cabins?$"),
            CapacityDim.BERTHS to Regex("\\s*berths?$"),
            CapacityDim.HEADS to Regex("\\s*(?:wc|toilets?|heads?)$"),
        )
    private val ENCLOSED = Regex("^\\((.*)\\)$")
    private val LEADING_EXTRA = Regex("^[+\\-–/]")
    private val LEADING_PLUS_WORD = Regex("^plus\\b")
    private val PART = Regex("^(\\d{1,3})\\s*([a-z' ]*)$")
    private val BLANKS = Regex("\\s+")

    fun parse(
        value: Int?,
        note: String?,
        dim: CapacityDim,
    ): CapacitySplit? {
        if (value == null || value <= 0) return null
        val normalized = CapacityText.normalizeNote(note) ?: return null

        var s = normalized.lowercase()
        ENCLOSED.find(s)?.let { s = it.groupValues[1].trim() }
        if (LEADING_EXTRA.containsMatchIn(s) || LEADING_PLUS_WORD.containsMatchIn(s) || s.contains('/')) return null

        val parts = s.split('+').map { it.trim() }
        if (parts.size < 2) return null

        var guests: Int? = null
        var crew: Int? = null
        var skipper: Int? = null
        var saloon: Int? = null
        var sum = 0
        parts.forEachIndexed { i, part ->
            val m = PART.find(part) ?: return null
            val count = m.groupValues[1].toInt()
            val label = m.groupValues[2].replace(BLANKS, " ").trim()
            if (count <= 0) return null
            sum += count

            if (i == 0) {
                if (label !in GUEST_LABELS) return null
                guests = count
                return@forEachIndexed
            }
            if (label.isEmpty()) return null
            val role = DIM_NOUN.getValue(dim).replaceFirst(label, "").trim()
            when {
                role in CREW_ROLES -> crew = (crew ?: 0) + count
                role in SKIPPER_ROLES -> skipper = (skipper ?: 0) + count
                dim == CapacityDim.BERTHS && role in SALOON_ROLES -> saloon = (saloon ?: 0) + count
                else -> return null
            }
        }

        return if (sum == value) CapacitySplit(guests = guests, saloon = saloon, crew = crew, skipper = skipper) else null
    }

    /**
     * NauSys berths: berthsCabin + berthsSalon + berthsCrew must equal berthsTotal (held on 820/820 in the sample) and at
     * least two parts must be > 0; otherwise null (the figure is then shown alone).
     */
    fun nausysBerthsSplit(
        total: Int?,
        inCabins: Int?,
        saloon: Int?,
        crew: Int?,
    ): CapacitySplit? {
        if (total == null || total <= 0 || inCabins == null || saloon == null) return null
        val c = crew ?: 0
        if (inCabins < 0 || saloon < 0 || c < 0 || inCabins + saloon + c != total) return null

        val split =
            CapacitySplit(
                inCabins = inCabins.takeIf { it > 0 },
                saloon = saloon.takeIf { it > 0 },
                crew = c.takeIf { it > 0 },
            )
        val parts = listOfNotNull(split.inCabins, split.saloon, split.crew).size
        return if (parts >= 2) split else null
    }
}
