package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.stereotype.Component

/**
 * Strips noisy cabin-configuration suffixes from partner-supplied model names
 * so MMK + NauSys cannot fork "Bali 4.4" into a dozen variants
 * ("Bali 4.4 - 3 + 1 cab.", "Bali 4.4 - 4 + 1 cab.", "Bali 4.4 - 4 + 2 cab.*"
 * etc.). Mario rule (3.5.2026): "svi ovi trebaju biti pod Bali 4.4".
 *
 * What gets stripped:
 *   ` - N cab.`               → bare cabin count
 *   ` - N + M cab.`           → bunk + cabin count, with or without spaces
 *   ` - N+M cab.`             → no-space variant
 *   trailing `*` after cab.   → MMK uses asterisk to flag a sub-variant
 *   trailing whitespace
 *
 * What is preserved (NOT stripped):
 *   ` DC` / ` OW` / ` MY` / ` Fly` / ` Sport` / ` Iconic` / ` Premium` etc.
 *   — these are real model variants (different deck layout / engine), not
 *   cabin-config noise. Merging them would lose product distinctions Mario
 *   wants kept.
 *
 * Comparison is done on a normalised key (trim + lowercase) so accent and
 * whitespace differences land on the same row in the manufacturer×name
 * lookup downstream.
 */
@Component
class ModelNameNormaliser {

    private val cabinSuffixPattern = Regex(
        " - \\d+(\\s*\\+\\s*\\d+)?\\s*cab\\.?\\*?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns the canonical model name for a partner-supplied raw name —
     * trimmed, with cabin-suffix stripped. Empty input returns empty.
     */
    fun canonicalName(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return trimmed
        return cabinSuffixPattern.replace(trimmed, "").trim()
    }
}
