package hr.workspace.boat4you.domains.catalouge.utils

/**
 * Maps a partner's own wording of an extra onto our canonical label before the name is stored.
 *
 * Partner agencies (MMK, NauSys) name their extras freely. NSS Charter ("One man crew (Caribbean)") and Marina Yacht
 * Charter ("One man crew (+ boarding)") sell the skipper as "One man crew", so it did not read as a skipper on the
 * customer extras tab or in e-mails, and the admin offer builder - which finds the skipper row by the keyword
 * "skipper" - did not find it at all (Mario 24.9.2026: show it as Skipper everywhere).
 *
 * Applied where the yacht / offer sync WRITES the name (MMK on insert and update, NauSys on insert only) and where a
 * name is read (PartnerCopySanitizer.extraName for display, OfferCharterRules.roleOf). Everything that MATCHES a
 * partner extra (catalogue `match_keys`, externalId, payment-type keywords) keeps using the raw partner name.
 * A row written before a rule existed keeps its old name until a sync rewrites it (V9_60 backfilled "One man crew";
 * a later rule's comment says where its backfill is), so code that joins a stored name to a partner name compares
 * both spellings (NauSysObligatoryExtrasService, PriceCalculationService.mergeNausysObligatory).
 *
 * A name no rule matches comes back byte-for-byte unchanged (no general trim / whitespace cleanup): stored names are
 * join keys (extrasKey() falls back to the name, NauSysObligatoryExtrasService maps names back to NauSys services).
 */
object ExtraNameNormalizer {
    /** Whitespace incl. the no-break space (a decoded &nbsp;): Java's `\s` is ASCII-only. */
    private const val WS = """[\s\xA0]"""

    /**
     * Partner phrase (whole words, case-insensitive unless the row says otherwise) -> our label. Add a row to cover
     * another partner wording.
     */
    val SYNONYMS: List<Pair<Regex, String>> =
        listOf(
            // "One man crew", "one-man crew", "one  man crew", "oneman crew"
            Regex("""\bone[\s-]*man[\s-]*crew\b""", RegexOption.IGNORE_CASE) to "Skipper",
            // 21.10.2026 (EY SEO regression T12): "Early check in (at 13:00) RESERVED IN ADVANCE" reached the sites
            // verbatim. An ALL-CAPS trailing "RESERVED IN ADVANCE" (with "!" / ".", in brackets, after or between
            // dashes) becomes "(reserved in advance)"; its own brackets and dashes go, so it is never double-bracketed.
            // Case-SENSITIVE on purpose: a name that already reads "reserved in advance" and every other word or
            // acronym (APA, VAT, VHF ...) stay as they are. No SQL twin like V9_60: the backfill is in DEPLOY_NOTES.
            Regex(
                """$WS*(?:[\p{Pd}:]+$WS*)?(?:[(\[]$WS*RESERVED$WS+IN$WS+ADVANCE$WS*[!.]*$WS*[)\]]|\bRESERVED$WS+IN$WS+ADVANCE\b$WS*[!.]*)$WS*\p{Pd}*$WS*$""",
            ) to " (reserved in advance)",
        )

    private val repeatedWhitespace = Regex("""\s{2,}""")

    fun normalize(name: String?): String? {
        if (name.isNullOrBlank()) return name
        val replaced = SYNONYMS.fold(name) { acc, (pattern, label) -> pattern.replace(acc) { label } }
        return if (replaced == name) name else replaced.replace(repeatedWhitespace, " ").trim()
    }
}
