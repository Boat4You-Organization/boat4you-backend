package hr.workspace.boat4you.domains.catalouge.capacity

/**
 * The rules of [PartnerTextSanitizer.capacityNote] without the name lists (capacity contract v1, section 6), so they can
 * be tested on their own. They HIDE and never rewrite: the result is the normalized note or null, the number still
 * shows. Unlike the sites' safePartnerText there is no "must contain a letter" check and no tidying, so "+2", "/5",
 * "8+2" and "- 4 double ..." survive verbatim (critique B-1). The frontends' yachtCapacity.ts `safeCapacityNote` is
 * the same rule set.
 */
object CapacityNoteRules {
    private const val W_BEFORE = "(?<![\\p{L}\\p{N}_])"
    private const val W_AFTER = "(?![\\p{L}\\p{N}_])"

    /** b4y HARD_WORDS + PROSE_WORDS, sister COMPANY_WORDS_RX, b4y NOTE_WORDS + sister INTERNAL_NOTE_RX (whole text). */
    val BLOCKED_WORDS: List<String> =
        listOf(
            // operator voice, contract, premises (b4y HARD + sisters COMPANY)
            "liable",
            "liability",
            "charterers?",
            "the company",
            "manually",
            "to be (?:confirmed|updated|checked|defined|added|agreed|approved)",
            "tb[acd]",
            "piers?",
            "pontoons?",
            "ponton",
            "nausys",
            "mmk",
            "booking ?manager",
            "nss",
            // partner prose / company (b4y PROSE + sisters COMPANY)
            "yachts",
            "yachting",
            "charters?",
            "sailing",
            "ltd",
            "d\\.\\s?o\\.\\s?o\\.?",
            "bases?",
            "agency",
            "owners?",
            "office",
            "our",
            "we",
            "allowance",
            "moorings",
            // internal / back-office notes (b4y NOTE + sisters INTERNAL_NOTE)
            "internal",
            "notes?",
            "update[ds]?",
            "to update",
            "check with",
            "ask (?:the )?(?:base|office|agency|owner)",
            "agents?",
            "brokers?",
            "b2b",
            "commission",
            "admin",
            "office use",
            "net price",
            "do not (?:show|publish)",
            "not for (?:the )?clients?",
            "charter company",
            // literal junk tokens
            "null",
            "undefined",
            "nan",
        )

    private val BLOCKED = Regex("$W_BEFORE(?:${BLOCKED_WORDS.joinToString("|")})$W_AFTER", RegexOption.IGNORE_CASE)
    private val SHOUTING = Regex("[!?]{2,}")
    private val MARKUP = Regex("[<>{}\\\\`]|[\\u0000-\\u001f\\u007f\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u2064\\ufeff]")
    private val EMAIL = Regex("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+\\.[\\p{L}\\p{N}.-]+")
    private val WEB =
        Regex(
            "\\bhttps?://|\\bwww\\.|\\b[\\p{L}\\p{N}-]{2,}\\.(?:com|net|org|eu|hr|gr|it|de|es|fr|pt|me|tr|co|io|uk|info|biz|travel)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val CONTACT_WORD = Regex("\\b(?:tel|phone|mob|mobile|whats ?app|viber)\\b[.:]?\\s*\\+?\\d", RegexOption.IGNORE_CASE)
    private val PHONE_RUN = Regex("\\+?\\(?\\d[\\d\\s()./-]{6,}\\d")
    private val PHONE_TRUNK = Regex("^[+(]*0|^\\+")
    private val LONG_NUMBER = Regex("\\d[\\d\\s()./-]{8,}\\d")
    private val NON_DIGIT = Regex("\\D")

    private fun hasPhone(text: String): Boolean =
        PHONE_RUN.findAll(text).any { match ->
            val run = match.value
            val digits = run.replace(NON_DIGIT, "").length
            digits >= 9 || (digits >= 8 && PHONE_TRUNK.containsMatchIn(run))
        }

    /** The note as a public surface may show it, or null. [isName] = operator / agency name matcher. */
    fun capacityNote(
        raw: String?,
        isName: (String) -> Boolean,
    ): String? {
        val text = CapacityText.normalizeNote(raw) ?: return null
        return when {
            text.length > CapacityText.NOTE_MAX -> null
            MARKUP.containsMatchIn(text) -> null
            BLOCKED.containsMatchIn(text) || SHOUTING.containsMatchIn(text) -> null
            EMAIL.containsMatchIn(text) || WEB.containsMatchIn(text) || CONTACT_WORD.containsMatchIn(text) -> null
            hasPhone(text) || LONG_NUMBER.containsMatchIn(text) -> null
            isName(text) -> null
            else -> text
        }
    }
}
