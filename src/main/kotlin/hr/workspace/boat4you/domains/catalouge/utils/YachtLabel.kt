package hr.workspace.boat4you.domains.catalouge.utils

import java.text.Normalizer

/**
 * R31 (re-audit 29.9.2026): a boat whose name only repeats its model ("Gulet" / "Gulet", "MY Custom Anthea" / "Anthea")
 * is labelled by its model alone. Kotlin port of the b4y web rule (`src/utils/static/toTitleCase.ts` `nameCore`,
 * `nameRepeatsModel`, dcbec3ae8) - keep the two in step; [YachtLabelTest] carries the web's cases.
 *
 * Used by the slug (SlugUtils) and the reservation / review e-mails. The API `name` field is unchanged (never null).
 */
object YachtLabel {
    // Vessel-type prefix after the punctuation fold: "M/S", "MS", "M/Y", "MY", "S/Y", "SY", "MSY", "M/V", "MV", "S/V", "SV".
    private val VESSEL_PREFIX_FOLDED = Regex("^(?:m s|m y|s y|m v|s v|msy|ms|my|sy|mv|sv)\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

    /** Comparison form: lowercase, no accents, punctuation -> spaces, trimmed, no vessel-type prefix. */
    fun nameCore(value: String?): String =
        Normalizer
            .normalize((value ?: "").lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(NOT_LETTER_OR_DIGIT, " ")
            .trim()
            .replaceFirst(VESSEL_PREFIX_FOLDED, "")

    /**
     * True when the boat's name adds nothing to its model: the same words, or the model's last words - case,
     * punctuation and a vessel-type prefix ignored. "Lagoon 42" / "My Lagoon" keeps its name.
     */
    fun nameRepeatsModel(
        model: String?,
        name: String?,
    ): Boolean {
        val modelCore = nameCore(model)
        val ownName = nameCore(name)
        return modelCore.isNotEmpty() && ownName.isNotEmpty() && (modelCore == ownName || modelCore.endsWith(" $ownName"))
    }
}
