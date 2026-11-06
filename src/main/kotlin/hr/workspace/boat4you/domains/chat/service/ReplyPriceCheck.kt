package hr.workspace.boat4you.domains.chat.service

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mario 6.11.2026: IRIS wrote "Lagoon 51 €18,000 approx" although no search had returned that figure. The euro amounts
 * of a reply are read here and checked against what the chat's tools returned: a total, a share of a total (price per
 * person), the difference of two totals, or a figure the visitor wrote themselves (their budget). Amounts under
 * [MIN_AMOUNT] (an extra, a deposit the model was told about) are not a charter's price and are not checked.
 */
object ReplyPriceCheck {
    const val MIN_AMOUNT = 500

    /** A per-person share of a total: for groups of 2 to this many. */
    private const val MAX_SHARE = 16

    /** A number with thousands groups ("13,357", "14.582", "9 950") or without; a group never runs into the next digits. */
    private const val NUMBER = """\d{1,3}(?:[.,\u00A0\u202F ]\d{3}(?!\d))+(?:[.,]\d{1,2}(?!\d))?|\d+(?:[.,]\d{1,2}(?!\d))?"""
    private const val CURRENCY = """(?:€|\beur(?:os?|a)?\b)"""
    private val CURRENCY_FIRST = Regex("""(?:€|\beur(?:os?|a)?)\s?($NUMBER)(?:\s?(k)(?!\p{L}))?""", RegexOption.IGNORE_CASE)
    private val CURRENCY_AFTER = Regex("""(?<![\d.,])($NUMBER)\s?(k)?\s?$CURRENCY""", RegexOption.IGNORE_CASE)
    private val ANY_NUMBER = Regex("""(?<![\d.,])($NUMBER)(?:\s?(k)(?!\p{L}))?""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""^(?:19|20)\d\d$""")

    /** A sentence ends before a capital letter (so "approx. €18,000" stays one sentence). */
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+(?=\p{Lu})""")

    /**
     * The euro amounts of [text] of at least [MIN_AMOUNT], in the order they appear ("€13,357", "14.582 €", "€18k",
     * "EUR18000"). A currency sign between two numbers belongs to the one before it ("13,357 € 2027"); a bare year never
     * takes the sign ("2027 €13,357").
     */
    fun amounts(text: String): List<Int> {
        val after = CURRENCY_AFTER.findAll(text).filterNot { YEAR.matches(it.groupValues[1]) }.toList()
        val first = CURRENCY_FIRST.findAll(text).filter { m -> after.none { m.range.first in it.range } }.toList()
        return (after + first)
            .sortedBy { it.range.first }
            .mapNotNull { m -> value(m.groupValues[1], m.groupValues[2].isNotEmpty()) }
            .filter { it >= MIN_AMOUNT }
    }

    /** The amounts of [reply] that no tool total in [known] and nothing in [visitorText] accounts for. */
    fun unverified(reply: String, known: Collection<Int>, visitorText: String): List<Int> {
        val totals = known.filter { it > 0 }.distinct()
        val visitor = ANY_NUMBER.findAll(visitorText).mapNotNull { value(it.groupValues[1], it.groupValues[2].isNotEmpty()) }.toList()
        return amounts(reply).filterNot { a ->
            totals.any { abs(a - it) <= 1 } ||
                visitor.any { abs(a - it) <= 1 } ||
                totals.any { t -> (2..MAX_SHARE).any { n -> near(a, t.toDouble() / n) } } ||
                totals.any { t1 -> totals.any { t2 -> t1 > t2 && near(a, (t1 - t2).toDouble()) } }
        }.distinct()
    }

    /** [reply] without the sentences that name one of the [bad] amounts; lines are kept apart. */
    fun withoutAmounts(reply: String, bad: Collection<Int>): String =
        reply.lines().mapNotNull { line ->
            line.split(SENTENCE_END).filterNot { s -> amounts(s).any { a -> bad.any { abs(a - it) <= 1 } } }
                .joinToString(" ").trim().takeIf { it.isNotEmpty() }
        }.joinToString("\n")

    /** Within 1 % (at least 1 €) of [exact]: a share or a difference the model rounded ("about €1,670"). */
    private fun near(amount: Int, exact: Double): Boolean = abs(amount - exact) <= maxOf(1.0, exact * 0.01)

    /** "13,357" / "13.357" / "9 950" = 13357 / 13357 / 9950; "13,357.50" = 13358; "2.5" with [thousands] ("2.5k") = 2500. */
    private fun value(raw: String, thousands: Boolean): Int? {
        val decimal = Regex("""[.,](\d{1,2})$""").find(raw)?.takeIf { !Regex("""[.,]\d{3}$""").containsMatchIn(raw) }
        val whole = (decimal?.let { raw.substring(0, it.range.first) } ?: raw).replace(Regex("""[.,   ]"""), "")
        if (whole.isEmpty() || whole.length > 9) return null
        val number = BigDecimal(whole + (decimal?.let { "." + it.groupValues[1] } ?: ""))
        return (if (thousands) number.multiply(BigDecimal(1000)) else number).setScale(0, RoundingMode.HALF_UP).toDouble().roundToInt()
    }
}
