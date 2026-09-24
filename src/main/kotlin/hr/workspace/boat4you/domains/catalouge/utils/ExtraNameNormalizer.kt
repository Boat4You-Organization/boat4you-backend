package hr.workspace.boat4you.domains.catalouge.utils

/**
 * Maps a partner's own wording of an extra onto our canonical label before the name is stored.
 *
 * Partner agencies (MMK, NauSys) name their extras freely. NSS Charter ("One man crew (Caribbean)") and Marina Yacht
 * Charter ("One man crew (+ boarding)") sell the skipper as "One man crew", so it did not read as a skipper on the
 * customer extras tab or in e-mails, and the admin offer builder - which finds the skipper row by the keyword
 * "skipper" - did not find it at all (Mario 24.9.2026: show it as Skipper everywhere).
 *
 * Applied only where the yacht / offer sync WRITES the name (MMK + NauSys, on insert). Everything that MATCHES a
 * partner extra (catalogue `match_keys`, externalId, payment-type keywords) keeps using the raw partner name.
 * V9_60 applied the same rule to the rows that already existed - keep its SQL pattern in step when adding a rule.
 *
 * A name no rule matches comes back byte-for-byte unchanged (no general trim / whitespace cleanup): stored names are
 * join keys (extrasKey() falls back to the name, NauSysObligatoryExtrasService maps names back to NauSys services).
 */
object ExtraNameNormalizer {
    /** Partner phrase (whole words, case-insensitive) -> our label. Add a row to cover another partner wording. */
    val SYNONYMS: List<Pair<Regex, String>> =
        listOf(
            // "One man crew", "one-man crew", "one  man crew", "oneman crew"
            Regex("""\bone[\s-]*man[\s-]*crew\b""", RegexOption.IGNORE_CASE) to "Skipper",
        )

    private val repeatedWhitespace = Regex("""\s{2,}""")

    fun normalize(name: String?): String? {
        if (name.isNullOrBlank()) return name
        val replaced = SYNONYMS.fold(name) { acc, (pattern, label) -> pattern.replace(acc) { label } }
        return if (replaced == name) name else replaced.replace(repeatedWhitespace, " ").trim()
    }
}
