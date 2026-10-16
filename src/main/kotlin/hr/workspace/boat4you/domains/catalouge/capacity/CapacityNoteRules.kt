package hr.workspace.boat4you.domains.catalouge.capacity

import java.text.Normalizer

/**
 * The rules of [PartnerTextSanitizer.capacityNote] without the name lists (capacity contract v1, section 6), so they can
 * be tested on their own. They HIDE and never rewrite: the result is the normalized note or null, the number still
 * shows. Unlike the sites' safePartnerText there is no "must contain a letter" check and no tidying, so "+2", "/5",
 * "8+2" and "- 4 double ..." survive verbatim (critique B-1). The frontends' yachtCapacity.ts `safeCapacityNote` is
 * the 6.10.2026 rule set ([BLOCKED_WORDS] and the contact / markup / length checks), their optional second line; the
 * rules added after the native review of 15.10.2026 ([PRICE_WORDS], currency, the owner abbreviation, and for the three
 * capacity figures an engine spec or the yacht's own name) run here only, the one gate every surface reads.
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

    /**
     * A price obligation (native review 15.10.2026; owner rule: no prices or surcharges in a capacity note, only the real
     * price is shown): "Price is for up to 12 pax. For 13 - 18 pax (in shared cabins) surcharge applies." promises a
     * charge the shown price does not carry. English plus the partners' other languages (it / es / fr / de / hr).
     */
    val PRICE_WORDS: List<String> =
        listOf(
            "prices?",
            "priced",
            "surcharges?",
            "supplements?",
            "supplemento",
            "suplementos?",
            "suppléments?",
            "zuschlags?",
            "aufpreis",
            "doplat\\p{L}*",
            "fees?",
            "payable",
            "paid",
            "payments?",
            "extra (?:charges?|costs?)",
        )

    private val BLOCKED = Regex("$W_BEFORE(?:${BLOCKED_WORDS.joinToString("|")})$W_AFTER", RegexOption.IGNORE_CASE)
    private val PRICE = Regex("$W_BEFORE(?:${PRICE_WORDS.joinToString("|")})$W_AFTER", RegexOption.IGNORE_CASE)

    /** Any currency symbol, or a currency code also right after a number ("150EUR"). */
    private val CURRENCY = Regex("\\p{Sc}|(?<![\\p{L}_])(?:eur|euros?|usd|gbp)(?![\\p{L}_])", RegexOption.IGNORE_CASE)

    /**
     * The owner abbreviation that "owners?" misses because a digit is glued to it: "(1OWN+2D+1BB)" showed the owner's
     * cabin in translation ("vlasnička", "Eignerkabine") on 5 boats, review 15.10.2026. OWN in capitals as a token, or own
     * right after a digit; the English "with own bathroom" stays (in an all-capitals note it is hidden - the number shows).
     */
    private val OWNER_ABBREVIATION = Regex("(?<![\\p{L}_])OWN(?![\\p{L}_])|(?<=\\p{N})(?i:own)(?![\\p{L}_])")

    /**
     * Cabins / berths / heads only: an engine maker or a power with its unit is a rig fact the partner typed into the
     * wrong field ("Yanmar 20hp" as a WC note, review 15.10.2026). PS and CV are left out (port side, convertible); a
     * maker with them is still caught by its name. Engine labels themselves never get this rule.
     */
    private val ENGINE_MAKER =
        Regex(
            "$W_BEFORE(?:yanmar|volvo|penta|nanni|perkins|lombardini|vetus|cummins|caterpillar|mtu|tohatsu|mercury|mercruiser|" +
                "suzuki|yamaha|honda)$W_AFTER",
            RegexOption.IGNORE_CASE,
        )
    private val ENGINE_POWER = Regex("\\d\\s?(?:bhp|b\\.h\\.p\\.?|h\\.p\\.?|hp|kw|ks)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** A yacht name shorter than this (folded) is never looked for in a note. */
    private const val MIN_YACHT_NAME = 3

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

    /** Hidden on every surface: markup, a blocked or price word, shouting, the owner abbreviation, contact data. */
    private val TEXT_RULES: List<Regex> =
        listOf(MARKUP, BLOCKED, SHOUTING, PRICE, CURRENCY, OWNER_ABBREVIATION, EMAIL, WEB, CONTACT_WORD, LONG_NUMBER)

    /** Hidden in a cabins / berths / heads note only: an engine spec. */
    private val FIGURE_RULES: List<Regex> = listOf(ENGINE_MAKER, ENGINE_POWER)

    private fun hasPhone(text: String): Boolean =
        PHONE_RUN.findAll(text).any { match ->
            val run = match.value
            val digits = run.replace(NON_DIGIT, "").length
            digits >= 9 || (digits >= 8 && PHONE_TRUNK.containsMatchIn(run))
        }

    /**
     * True when the note names the yacht itself ("Dea Del Mare has 5 Cabins; ..."): one of [yachtNames] (the public and
     * the raw partner name), folded like the operator names, as whole words. A name made only of capacity words or
     * digits ("Master", "008") never counts.
     */
    private fun namesYacht(
        probe: String,
        yachtNames: Collection<String?>,
    ): Boolean {
        if (yachtNames.isEmpty()) return false
        val text = " ${PartnerNameMatcher.fold(probe)} "
        return yachtNames.any { raw ->
            val name = raw?.let { PartnerNameMatcher.fold(it) } ?: return@any false
            name.length >= MIN_YACHT_NAME && !PartnerNameMatcher.isCapacityPhrase(name) && text.contains(" $name ")
        }
    }

    /**
     * The note as a public surface may show it, or null. [isName] = operator / agency name matcher. [dim] is set for a
     * cabins / berths / heads note (null for an engine or sail label): only there an engine spec is hidden. [yachtNames]
     * = the boat's own names, hidden in a note. The checks run on the NFKC form, so fullwidth or other compatibility
     * characters cannot slip past them ("example．com", "ＴＥＬ"); the text returned is the normalized note itself, never
     * that form.
     */
    fun capacityNote(
        raw: String?,
        dim: CapacityDim? = null,
        yachtNames: Collection<String?> = emptyList(),
        isName: (String) -> Boolean,
    ): String? {
        val text = CapacityText.normalizeNote(raw) ?: return null
        val probe = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return when {
            text.length > CapacityText.NOTE_MAX || probe.length > CapacityText.NOTE_MAX -> null
            TEXT_RULES.any { it.containsMatchIn(probe) } || hasPhone(probe) -> null
            dim != null && FIGURE_RULES.any { it.containsMatchIn(probe) } -> null
            namesYacht(probe, yachtNames) -> null
            isName(probe) -> null
            else -> text
        }
    }
}
