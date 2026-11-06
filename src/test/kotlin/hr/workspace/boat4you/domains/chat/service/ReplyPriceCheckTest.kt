package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Mario 6.11.2026: IRIS wrote "Lagoon 51 €18,000 approx" although no search had returned that figure. Every euro
 * amount of a reply must be a total a tool returned in this chat, a share of one (price per person), or a figure the
 * visitor wrote themselves (their budget).
 */
class ReplyPriceCheckTest {
    private val known = listOf(13357, 14582, 8721)

    @Test
    fun `the amounts of a reply are read in every usual notation`() {
        ReplyPriceCheck.amounts("the Bali at €13,357, the Lagoon at 14.582 €, another at EUR 8721 and one at 9 950 EUR") shouldBe
            listOf(13357, 14582, 8721, 9950)
        ReplyPriceCheck.amounts("around €18k, or 2.5k EUR") shouldBe listOf(18000, 2500)
        ReplyPriceCheck.amounts("€13,357.50") shouldBe listOf(13358)
    }

    @Test
    fun `small amounts and numbers without a currency are not prices of a charter`() {
        ReplyPriceCheck.amounts("cleaning €150, 4 cabins, 46 feet, 19.-26.6.2027, 12 people").shouldBeEmpty()
    }

    @Test
    fun `an amount no tool returned is flagged`() {
        ReplyPriceCheck.unverified("Bali 4.8 for €13,357 and the Lagoon 51 at about €18,000.", known, "") shouldBe listOf(18000)
    }

    @Test
    fun `totals, shares of a total and the visitor's own figures pass`() {
        ReplyPriceCheck.unverified("€13,357 in total, about €1,670 per person for 8 of you", known, "").shouldBeEmpty()
        ReplyPriceCheck.unverified("all of them are under your €10,000 budget", known, "we have 10k max").shouldBeEmpty()
        ReplyPriceCheck.unverified("all of them are under your €10,000 budget", known, "budget 10.000 eur").shouldBeEmpty()
        ReplyPriceCheck.unverified("€8,721 for the week", known, "").shouldBeEmpty()
    }
}
