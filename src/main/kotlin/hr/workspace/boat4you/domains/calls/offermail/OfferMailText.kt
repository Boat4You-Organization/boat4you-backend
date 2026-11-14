package hr.workspace.boat4you.domains.calls.offermail

import hr.workspace.boat4you.domains.catalouge.capacity.PartnerNameMatcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.text.Normalizer

/**
 * The offer e-mail as Nika may read it (Mario 14.11.2026: "ona mora znat sve napisano"): the whole text Mario wrote, without
 * the quoted earlier mails, the signature and any charter company name. Pure functions, so every rule has a test.
 */
object OfferMailText {
    const val MAX = 8000
    const val PARTNER = "[charter company]"

    /** Lines that open the quoted earlier mail in Apple Mail, Gmail, Outlook and the client languages we get. */
    private val QUOTE_HEADER =
        listOf(
            Regex("""^On .{3,200}wrote:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^Am .{3,200}schrieb.{0,80}:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^Le .{3,200}a écrit\s*:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^Il giorno .{3,200}ha scritto:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^El .{3,200}escribió:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^Op .{3,200}schreef.{0,80}:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^\S.{0,200}(je napisao|je napisala|napisao je|napisala je).{0,40}:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^-{2,}\s*Original Message\s*-{2,}\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^Begin forwarded message:\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^-{2,}\s*Forwarded message\s*-{2,}\s*$""", RegexOption.IGNORE_CASE),
        )
    /** An Outlook-style quoted header: a From line with an address, followed by a Sent/Date line ("To:" alone is not enough). */
    private val OUTLOOK_FROM = Regex("""^(From|Von|De|Od|Da):\s+.*(@|<).*$""", RegexOption.IGNORE_CASE)
    private val OUTLOOK_NEXT =
        Regex("""^(Sent|Date|Gesendet|Envoyé|Poslano|Datum|Inviato|Enviado|Data):\s+.*$""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""[\p{L}\p{M}\p{N}]+|&""")
    private val DIGIT = Regex("""\d""")
    private val BLANK_LINE = Regex("""\n[ \t]+\n""")
    private val MANY_NEWLINES = Regex("""\n{3,}""")

    /** The body Mario wrote: no quoted mails, no signature, at most [MAX] characters. [html] when only an HTML part exists. */
    fun clean(
        body: String,
        html: Boolean,
    ): String {
        val lines = nfc(plain(body, html)).replace("\r\n", "\n").replace('\r', '\n').lines()
        val kept = mutableListOf<String>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd().replace('\u00A0', ' ')
            val trimmed = line.trim()
            if (raw == "-- ") break // the signature separator only; a bare "--" may be Mario's own divider
            if (QUOTE_HEADER.any { it.matches(trimmed) }) break
            if (OUTLOOK_FROM.matches(trimmed) && lines.drop(i + 1).take(3).any { OUTLOOK_NEXT.matches(it.trim()) }) break
            if (line.trimStart().startsWith(">")) continue
            kept += line
        }
        val joined =
            kept
                .joinToString("\n")
                .replace(BLANK_LINE, "\n\n")
                .replace(MANY_NEWLINES, "\n\n")
                .trim()
        if (joined.length <= MAX) return joined
        val cut = joined.take(MAX)
        return cut.substringBeforeLast('\n', cut).trimEnd()
    }

    /**
     * [text] with every name of [names] (folded, as [PartnerNameMatcher.findAll] gives them) replaced by [PARTNER]. A boat
     * builder's name ([builders], folded) stays only where a model follows it: a word with a digit or a model word
     * ([modelWords], folded): "Lagoon 42" and "Fountaine Pajot Elba" stay, "Lagoon Charter" and a bare "Lagoon" go. Returns
     * the text (NFC) and the names as they were written.
     */
    fun redact(
        input: String,
        names: Set<String>,
        builders: Set<String>,
        modelWords: Set<String> = emptySet(),
    ): Pair<String, List<String>> {
        val text = nfc(input)
        val targets = names.map { it.split(' ') }.sortedByDescending { it.size }
        if (targets.isEmpty()) return text to emptyList()
        val tokens = WORD.findAll(text).map { m -> Token(m.range.first, m.range.last + 1, fold(m.value)) }.toList()
        val spans = mutableListOf<IntRange>()
        val replaced = LinkedHashSet<String>()
        var i = 0
        while (i < tokens.size) {
            val hit = targets.firstOrNull { words -> words.indices.all { k -> tokens.getOrNull(i + k)?.folded == words[k] } }
            if (hit == null) {
                i++
                continue
            }
            val next = tokens.getOrNull(i + hit.size)?.folded
            if (hit.joinToString(" ") in builders && next != null && (DIGIT.containsMatchIn(next) || next in modelWords)) {
                i += hit.size
                continue
            }
            val end = tokens[i + hit.size - 1].end
            spans += tokens[i].start until end
            replaced += text.substring(tokens[i].start, end)
            i += hit.size
        }
        val out = StringBuilder(text)
        spans.asReversed().forEach { out.replace(it.first, it.last + 1, PARTNER) }
        return out.toString() to replaced.toList()
    }

    private data class Token(
        val start: Int,
        val end: Int,
        val folded: String,
    )

    /**
     * Fail-closed pass after [redact]: every line in which [leaks] still finds a name is replaced by [PARTNER] as a whole
     * (a spelling the word split did not catch). Returns the text and how many lines were replaced.
     */
    fun dropLeakingLines(
        text: String,
        leaks: (String) -> Boolean,
    ): Pair<String, Int> {
        var dropped = 0
        val out =
            text.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && leaks(line)) {
                    dropped++
                    PARTNER
                } else {
                    line
                }
            }
        return out to dropped
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun fold(word: String) = if (word == "&") "and" else PartnerNameMatcher.fold(word).replace(" ", "")

    /** A body part as plain text: an HTML part loses its quoted mails and tags, keeping its line breaks. */
    fun plain(
        body: String,
        html: Boolean,
    ): String = if (html) htmlToText(body) else body

    private fun htmlToText(html: String): String {
        val doc = Jsoup.parse(html)
        // Outlook: the quoted mail starts at its marker and runs to the end, so the marker goes with everything after it.
        doc.select("#appendonsend, div[id^=divRplyFwdMsg], #divRplyFwdMsg, hr#stopSpelling").forEach { marker ->
            generateSequence(marker.nextElementSibling()) { it.nextElementSibling() }.toList().forEach { it.remove() }
            marker.remove()
        }
        doc.select("blockquote, .gmail_quote, style, script").remove()
        doc.outputSettings(Document.OutputSettings().prettyPrint(false))
        doc.select("br").after(NL)
        doc.select("p, div, tr, li, h1, h2, h3, h4, table").before(NL)
        val text = Jsoup.clean(doc.body().html(), "", Safelist.none(), Document.OutputSettings().prettyPrint(false))
        return Parser.unescapeEntities(text.replace(NL, "\n"), false)
    }

    /** A marker Jsoup keeps through clean(); turned into a real line break after it. */
    private const val NL = "\u2063NL\u2063"
}
