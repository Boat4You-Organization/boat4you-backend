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

    private const val NUMBER = """\d{1,3}(?:[.,   ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
    private val CURRENCY_FIRST = Regex("""(?:€|\bEUR\b|\beur\b)\s?($NUMBER)\s?([kK]\b)?""")
    private val CURRENCY_AFTER = Regex("""(?<![\d.,])($NUMBER)\s?([kK])?\s?(?:€|\bEUR\b|\beur\b|\beuros?\b)""", RegexOption.IGNORE_CASE)
    private val ANY_NUMBER = Regex("""(?<![\d.,])($NUMBER)\s?([kK]\b)?""")
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

    /** The euro amounts of [text] of at least [MIN_AMOUNT], in the order they appear ("€13,357", "14.582 €", "€18k"). */
    fun amounts(text: String): List<Int> =
        (CURRENCY_FIRST.findAll(text) + CURRENCY_AFTER.findAll(text))
            .sortedBy { it.range.first }
            .distinctBy { it.range.first }
            .mapNotNull { m -> value(m.groupValues[1], m.groupValues[2].isNotEmpty()) }
            .filter { it >= MIN_AMOUNT }
            .toList()

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
