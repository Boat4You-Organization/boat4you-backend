package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.enums.SailKind

/**
 * Partner sail labels (capacity contract v1, 5.4). MMK sends a text (mainsailType / genoaType: "Full batten",
 * "Furling", "Self tacking jib", "None"), NauSys an id of its sailTypes catalogue; both are stored as text in
 * yacht.mainsail_label / genoa_label and read into a [SailKind] here. The MMK label must come from the fleet call
 * WITHOUT ?language= ("Lattengroß" / "Rollgroß" for a genoa in German): a localized label would read as unknown.
 */
object SailLabels {
    /** NauSys sailTypes catalogue, EN names (live 6.10.2026). An id not listed here is stored as null (and logged). */
    val NAUSYS_SAIL_LABELS: Map<Int, String> =
        mapOf(
            1 to "furling/roll",
            3 to "full batten",
            4 to "classic/standard",
            112782 to "half batten",
            492236 to "self tacking jib",
            10403978 to "jib",
        )

    private val SEPARATORS = Regex("[-_]")
    private val BLANKS = Regex("\\s+")
    private val NONE_LABELS = setOf("none", "no", "n/a")
    private val CLASSIC_LABELS = setOf("classic", "standard", "classic/standard", "classic / standard")

    private fun key(label: String?): String {
        val normalized = CapacityText.normalizeNote(label) ?: return ""
        return normalized.lowercase().replace(SEPARATORS, " ").replace(BLANKS, " ")
    }

    /** The partner says the boat has no such sail ("None"): the row is hidden. */
    fun isNone(label: String?): Boolean = key(label) in NONE_LABELS

    /** The display kind of a partner label; null for a blank, "None" or a label outside the closed set. */
    fun kind(label: String?): SailKind? {
        val k = key(label)
        return when {
            k.isEmpty() || k in NONE_LABELS -> null
            k.contains("furl") || k.contains("roll") -> SailKind.FURLING
            k.contains("self tacking") || k.contains("selftacking") -> SailKind.SELF_TACKING_JIB
            k == "jib" || k == "jib sail" -> SailKind.JIB
            k.contains("semi full batten") || k.contains("semi batten") -> SailKind.SEMI_FULL_BATTEN
            k.contains("half batten") -> SailKind.HALF_BATTEN
            k.contains("full batten") -> SailKind.FULL_BATTEN
            k in CLASSIC_LABELS -> SailKind.CLASSIC
            else -> null
        }
    }
}
