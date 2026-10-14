package hr.workspace.boat4you.domains.catalouge.partner

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.utils.ExtraNameNormalizer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * R01 (owner rule 14.10.2026, "Di pise NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo"): partner text as a customer may read
 * it. A partner name from the CURATED list (resources/partner/replace.txt) becomes "Boat4You", internal partner notes
 * ("(number of guests to update manually)") go, premises ("at the Athenian's Pier") become neutral wording ("at the
 * charter base pier"; we have no pier or base, owner flag F5), and descriptions also lose contacts and the partner's
 * legal disclaimers (flag F2).
 *
 * Pure and Spring-free: the lists are read once from the classpath. Every function is null-safe and idempotent
 * (f(f(x)) == f(x)) and returns the very same String when no rule fires.
 *
 * Where it runs (DESIGN WS-R01):
 * - [description] at SYNC time in the extras / yacht_translations writers (EXTERNAL rows by construction) and at READ
 *   time for EXTERNAL yachts only (a CUSTOM yacht's copy is our own).
 * - [extraName] at READ time only, on the DTO / e-mail / PDF `name` field of an EXTERNAL yacht's extra. Never write it to
 *   a `name` column and never feed it to keys, merges or `ExtraPaymentType.classify`: the stored name is a join key
 *   (extrasKey(), carts, custom offers, reservation_extras.yacht_extras_key, the NauSys obligatory lookup; DESIGN D2).
 *
 * V9_83 is the SQL twin of the N2 + N3 + N4 + N5 subset of [description] for yacht_extras descriptions (frozen list
 * replace.v9_83.txt); R01MigrationTest keeps the two equal.
 */
object PartnerCopySanitizer {
    private val log = LoggerFactory.getLogger(PartnerCopySanitizer::class.java)

    const val BOAT4YOU = "Boat4You"
    const val REPLACE_RESOURCE = "partner/replace.txt"
    const val REPLACE_V9_83_RESOURCE = "partner/replace.v9_83.txt"
    const val PREMISES_RESOURCE = "partner/premises_names.txt"
    const val NEVER_REPLACE_RESOURCE = "partner/never_replace.txt"
    const val ALIASES_RESOURCE = "partner/replace_aliases.txt"

    /** A replace.txt line: the name, and whether it is replaced only before a model number or a possessive ("@model"). */
    data class Entry(
        val name: String,
        val beforeModelOrPossessive: Boolean,
    )

    /** One rule hit, for logs and the V9_83 / sizing counts. */
    data class Finding(
        val rule: String,
        val token: String,
    )

    fun readLines(resource: String): List<String> =
        (PartnerCopySanitizer::class.java.classLoader.getResourceAsStream(resource) ?: error("missing resource $resource"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

    fun parseEntries(lines: List<String>): List<Entry> =
        lines
            .map { line ->
                if (line.endsWith(MODEL_QUALIFIER)) {
                    Entry(line.removeSuffix(MODEL_QUALIFIER).trim(), true)
                } else {
                    Entry(line, false)
                }
            }.sortedByDescending { it.name.length }

    private const val MODEL_QUALIFIER = "@model"

    val replaceEntries: List<Entry> = parseEntries(readLines(REPLACE_RESOURCE))

    /** Premises-only names plus every replace.txt name, longest first. */
    val premisesNames: List<String> =
        (readLines(PREMISES_RESOURCE) + replaceEntries.map { it.name }).distinct().sortedByDescending { it.length }

    // ---- regex building blocks -----------------------------------------------------------------------------------

    private fun alternation(entries: List<Entry>): String =
        entries.joinToString("|") { e ->
            Regex.escape(e.name) + if (e.beforeModelOrPossessive) """(?=\s*[0-9]|['’]s\b)""" else ""
        }

    private val replaceAlt = alternation(replaceEntries)
    private val premisesAlt = premisesNames.joinToString("|") { Regex.escape(it) }

    /** Possessive marker: 's, ’s, or a bare apostrophe after a name ending in s ("Ionian Catamarans' pontoon"). */
    private const val POSS = """(?:'s|’s|'|’)"""
    private const val NOUN = """(?i:pier|pontoon|bases|base|office|premises|dock)"""

    // N2: internal partner notes (no customer meaning, no name)
    private val n2 =
        listOf(
            Regex("""\s*\((number of guests )?to (be )?update(d)? manually\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*[-–]\s*to be updated manually""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(internal\b[^)]*\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(!!\)"""),
        )

    // P1: premises -> neutral wording
    private val p1OneOf = Regex("""\bone of (?:[Tt]he )?(?:$premisesAlt)$POSS\s+$NOUN\b""")
    private val p1CommaIn = Regex("""\b(?:[Tt]he )?(?:$premisesAlt)$POSS\s+($NOUN),\s+in\b""")
    private val p1Possessive = Regex("""\b(?:[Tt]he )?(?:$premisesAlt)$POSS\s+($NOUN)\b""")
    private val p1Preposition = Regex("""\b(at|from|in|to) (?:the )?(?:$premisesAlt) ($NOUN)\b""")
    private val p1TheMoorings = Regex("""\bThe Moorings ($NOUN)\b""")
    private val theThe = Regex("""\b([Tt])he the\b""")

    // P2: legal disclaimer sentences that name a partner or "the Company"
    private val disclaimer =
        listOf(
            Regex("""\b(solely|only) provides?\b[^.]*\bservices\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(shall|will) not be (held )?liable\b""", RegexOption.IGNORE_CASE),
        )
    private val partnerMention = Regex("""\b(?:$premisesAlt)\b|\b[Tt]he Company\b""")
    private val sentenceBoundary = Regex("""(?<=[.!?])[ \t]+|\n""")

    // N3 / N4
    private val n3 = Regex("""([Tt])o be (?:re-?)?confirmed by (?:$replaceAlt)(?: office)?""")
    private val n4 = Regex("""\b(?:$replaceAlt)\b""")
    private val theCompany = Regex("""\b[Tt]he Company\b""")

    // N5
    private val n5 =
        listOf(
            Regex("""Boat4You\s*\(Boat4You\)""") to BOAT4YOU,
            Regex("""Boat4You(?:[\s/]+Boat4You)+""") to BOAT4YOU,
            Regex("""[ \t]*\([ \t]*\)""") to "",
            Regex("""[ \t]+[-–](?=[ \t]*(?:\)|\n|$))""") to "",
            Regex("""[ \t]+(?=[.,;:!?](?:\s|$))""") to "",
            Regex("""[ \t]{2,}""") to " ",
        )

    // contacts
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val url = Regex("""(?i)\b(?:https?://|www\.)[^\s<>()]*[^\s<>().,;:!?'"]""")
    private val bareDomain = Regex("""(?i)\b[a-z][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|net|org|gr|hr|it|me|tr|eu|es|fr|de|info)\b""")
    private val phone =
        Regex(
            """(?i)(?:\b(?:tel|phone|mob|mobile|call)\b\.?:?[ \t]*(?:\+[0-9]{1,3})?|\+[0-9]{1,3})[ \t.\-/]*\(?[0-9][0-9 \t.\-/()]*[0-9]""",
        )
    private const val PHONE_MIN_DIGITS = 8

    // tidy
    private val htmlTag = Regex("""<[a-zA-Z/][^>]*>""")
    private val htmlBreak = Regex("""(?i)<br\s*/?>|</p\s*>""")
    private val blankLines = Regex("""\n{3,}""")
    private val spaceRuns = Regex("""[ \t]{2,}""")
    private val premisesNoun = Regex("""(?i)pier|pontoon|base|office|premises|dock""")

    // ---- public API ------------------------------------------------------------------------------------------------

    /** Partner copy is cleaned only for EXTERNAL yachts; a CUSTOM yacht's copy is ours (DESIGN WS-R01, revision 7). */
    fun isPartnerCopy(entryType: EntryType?): Boolean = entryType == EntryType.EXTERNAL

    /** An extra's NAME as a customer reads it: N1, N2, P1, N3, N4, N5. Display only, never a key (see the class doc). */
    fun extraName(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        var s = ExtraNameNormalizer.normalize(raw)!!
        s = dropNotes(s)
        s = premises(s)
        s = n3.replace(s) { it.groupValues[1] + "o be confirmed by $BOAT4YOU" }
        s = n4.replace(s, BOAT4YOU)
        return if (s == raw) raw else collapse(s)
    }

    /** [extraName] for an EXTERNAL yacht's extra, the raw name otherwise. */
    fun extraName(
        raw: String?,
        partnerCopy: Boolean,
    ): String? = if (partnerCopy) extraName(raw) else raw

    /**
     * Partner prose (extras / services descriptions, boat description / highlights, equipment comments): tidy, N2,
     * contacts, P1, P2, N3, N4 + "the Company", N5. Null when nothing customer-worthy is left.
     */
    fun description(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = tidy(raw)
        s = dropNotes(s)
        s = dropContacts(s)
        s = premises(s)
        s = dropDisclaimers(s)
        s = n3.replace(s) { it.groupValues[1] + "o be confirmed by $BOAT4YOU" }
        s = n4.replace(s, BOAT4YOU)
        s = theCompany.replace(s, BOAT4YOU)
        return if (s == raw) raw else collapse(s).takeIf { it.isNotBlank() }
    }

    /** [description] for an EXTERNAL yacht's text, the raw text otherwise. */
    fun description(
        raw: String?,
        partnerCopy: Boolean,
    ): String? = if (partnerCopy) description(raw) else raw

    /**
     * Sync-time description writer (DESIGN WS-R01 "Where it is applied" 1): cleans the partner text and logs, once per
     * agency per process, when the cleaned text still carries the boat's own agency name that replace.txt does not list
     * (internal log only - the list grows by review, never automatically).
     */
    private fun syncDescription(
        raw: String?,
        agencyId: Long?,
        agencyName: String?,
    ): String? {
        val cleaned = description(raw)
        val candidate = unlistedAgencyName(cleaned, agencyName)
        if (candidate != null && agencyId != null && loggedAgencies.add(agencyId)) {
            log.info("R01 unlisted partner name candidate agency={} form=\"{}\"", agencyId, candidate)
        }
        return cleaned
    }

    /** [syncDescription] for a yacht of [agency] (the sync writers' form). */
    fun syncDescription(
        raw: String?,
        agency: Agency?,
    ): String? = syncDescription(raw, agency?.id, agency?.name)

    private val loggedAgencies: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val legalForm =
        Regex(
            """[\s,]+(d\.?\s?o\.?\s?o\.?|j\.?d\.?o\.?o\.?|ltd\.?|limited|llc|inc\.?|s\.?r\.?l\.?|s\.?a\.?|gmbh|ike|e\.?e\.?|epe|sas|sarl|b\.?v\.?)\s*$""",
            RegexOption.IGNORE_CASE,
        )

    /** The agency's name (without its legal form) when it still stands in [text] and is not on replace.txt. */
    fun unlistedAgencyName(
        text: String?,
        agencyName: String?,
    ): String? {
        val form = agencyName?.let { legalForm.replace(it.trim(), "").trim() }
        if (text.isNullOrBlank() || form == null || form.length < MIN_AGENCY_FORM) return null
        val listed = replaceEntries.any { it.name.equals(form, ignoreCase = true) }
        return form.takeIf { !listed && Regex("""\b${Regex.escape(form)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
    }

    private const val MIN_AGENCY_FORM = 5

    /** Which rules would fire on [text] and on what (for logs and counts). */
    fun findings(text: String?): List<Finding> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Finding>()

        fun scan(
            rule: String,
            regex: Regex,
        ) = regex.findAll(text).forEach { out += Finding(rule, it.value.trim()) }
        ExtraNameNormalizer.SYNONYMS.forEach { (pattern, _) -> scan("N1", pattern) }
        n2.forEach { scan("N2", it) }
        scan("CONTACT", email)
        scan("CONTACT", url)
        scan("CONTACT", bareDomain)
        phone.findAll(text).filter { digits(it.value) >= PHONE_MIN_DIGITS }.forEach { out += Finding("CONTACT", it.value.trim()) }
        listOf(p1OneOf, p1CommaIn, p1Possessive, p1Preposition, p1TheMoorings).forEach { scan("P1", it) }
        sentences(text).map { it.first }.filter { isDisclaimer(it) }.forEach { out += Finding("P2", it.trim()) }
        scan("N3", n3)
        scan("N4", n4)
        scan("N4", theCompany)
        return out
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    private fun tidy(s: String): String {
        var t = s
        if (htmlTag.containsMatchIn(t)) {
            t = htmlBreak.replace(t, "\n")
            t = htmlTag.replace(t, "")
            t = t.replace("&nbsp;", " ")
        }
        t = spaceRuns.replace(t, " ")
        return blankLines.replace(t, "\n\n")
    }

    private fun dropNotes(s: String): String = n2.fold(s) { acc, r -> r.replace(acc, "") }

    private fun digits(s: String): Int = s.count { it.isDigit() }

    private fun dropContacts(s: String): String {
        var t = email.replace(s, "")
        t = url.replace(t, "")
        t = bareDomain.replace(t, "")
        t = phone.replace(t) { m -> if (digits(m.value) >= PHONE_MIN_DIGITS) "" else m.value }
        return t
    }

    private fun neutralNoun(noun: String): String =
        when (noun.lowercase()) {
            "pier" -> "charter base pier"
            "pontoon" -> "charter base pontoon"
            "office" -> "charter base office"
            "dock" -> "charter base dock"
            else -> "charter base" // base, bases, premises
        }

    private fun premises(s: String): String {
        if (!premisesNoun.containsMatchIn(s)) return s
        var t = p1OneOf.replace(s) { m -> sentenceCase(s, m.range.first, "the charter base") }
        t = p1CommaIn.replace(t) { m ->
            val noun = m.groupValues[1].lowercase().let { if (it == "bases" || it == "premises") "base" else it }
            sentenceCase(t, m.range.first, if (noun == "base") "the base in" else "the base $noun in")
        }
        val afterComma = t
        t = p1Possessive.replace(afterComma) { m -> sentenceCase(afterComma, m.range.first, "the " + neutralNoun(m.groupValues[1])) }
        t = p1Preposition.replace(t) { m -> "${m.groupValues[1]} the " + neutralNoun(m.groupValues[2]) }
        val beforeMoorings = t
        t = p1TheMoorings.replace(beforeMoorings) { m -> sentenceCase(beforeMoorings, m.range.first, "the " + neutralNoun(m.groupValues[1])) }
        return theThe.replace(t) { m -> m.groupValues[1] + "he" }
    }

    /** Capitalises [replacement] when it starts a sentence of [text] at [index]. */
    private fun sentenceCase(
        text: String,
        index: Int,
        replacement: String,
    ): String {
        var i = index - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        val starts = i < 0 || text[i] in ".!?\n:("
        return if (starts && text.getOrNull(index)?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercase() } else replacement
    }

    private fun isDisclaimer(sentence: String): Boolean = disclaimer.any { it.containsMatchIn(sentence) } && partnerMention.containsMatchIn(sentence)

    /** The text cut into (sentence, separator after it) pairs; joining them gives the text back. */
    private fun sentences(s: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var start = 0
        sentenceBoundary.findAll(s).forEach { m ->
            out += s.substring(start, m.range.first) to m.value
            start = m.range.last + 1
        }
        out += s.substring(start) to ""
        return out
    }

    private fun dropDisclaimers(s: String): String {
        if (disclaimer.none { it.containsMatchIn(s) }) return s
        return sentences(s).filterNot { isDisclaimer(it.first) }.joinToString("") { it.first + it.second }
    }

    private fun collapse(s: String): String = n5.fold(s) { acc, (r, to) -> r.replace(acc, to) }.trim(' ', '\t', '\r', '\n')
}
