package hr.workspace.boat4you.domains.catalouge.equipment

import java.text.Normalizer
import java.util.Locale

/**
 * One normal form for partner equipment names and match keys (equipment audit 8.10.2026): accents stripped (NFD),
 * lower case, every run of characters that is neither a letter nor a digit becomes one space.
 * "Wi-Fi & Internet" -> "wi fi internet", "Wind instrument/Anemometer" -> "wind instrument anemometer",
 * "Jeneratör" -> "jenerator". partner_equipment_mapping.partner_name_norm holds this form for MMK free-text items.
 */
object EquipmentNames {
    private val MARKS = Regex("\\p{M}+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(name: String?): String {
        if (name == null) return ""
        val stripped = Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "")
        return stripped.lowercase(Locale.ROOT).replace(NOT_LETTER_OR_DIGIT, " ").trim()
    }

    fun tokens(name: String?): List<String> = normalize(name).let { if (it.isEmpty()) emptyList() else it.split(' ') }
}
