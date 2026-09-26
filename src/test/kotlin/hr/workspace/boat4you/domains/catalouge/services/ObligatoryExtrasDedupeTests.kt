package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.InternalCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObligatoryExtrasDedupeTests {
    private var nextId = 1L

    private fun row(
        name: String,
        price: String,
        obligatory: Boolean = true,
        extrasId: Long? = null,
        externalId: Long? = nextId + 1000,
    ) = OfferExtra().apply {
        id = nextId++
        this.name = name
        this.price = BigDecimal(price)
        this.obligatory = obligatory
        this.extrasId = extrasId
        this.externalId = externalId
    }

    private fun offer(vararg rows: OfferExtra) = Offer().apply { offerExtras = rows.toMutableList() }

    private fun names(offer: Offer) = offer.filterDuplicateExtras().map { it.first.name }.sortedBy { it }

    @Test
    fun `two partner charges under one catalogue key are both kept`() {
        // MMK: both matched to catalogue extra 1 (Skipper) by the fuzzy matcher.
        val o = offer(row("Skipper", "2940", extrasId = 1), row("Skipper's liability insurance", "350", extrasId = 1))
        assertEquals(listOf("Skipper", "Skipper's liability insurance"), names(o))
    }

    @Test
    fun `seasonal variants of one item stay one item - the dearest`() {
        val o =
            offer(
                row("Nineteen APA / High season (30%)", "10200", extrasId = 25),
                row("Nineteen APA/ Low season I  (30%)", "9300", extrasId = 25),
                row("Nineteen APA/ Low season II  (30%)", "9300", extrasId = 25),
            )
        val kept = o.filterDuplicateExtras()
        assertEquals(1, kept.size)
        assertEquals(BigDecimal("10200"), kept.single().first.price)
    }

    @Test
    fun `same name with different partner ids counts twice, a repeated partner row once`() {
        // NauSys lists "Preparation fee" twice with different ids and bills both.
        assertEquals(2, offer(row("Preparation fee", "440"), row("Preparation fee", "60")).filterDuplicateExtras().size)
        // The very same partner row (same id) never counts twice.
        assertEquals(1, offer(row("Cleaning", "150", externalId = 7), row("Cleaning", "150", externalId = 7)).filterDuplicateExtras().size)
        // No partner id: same name and price is one charge.
        assertEquals(1, offer(row("Cleaning", "150", externalId = null), row("Cleaning", "150.00", externalId = null)).filterDuplicateExtras().size)
    }

    @Test
    fun `names differing only in case or punctuation are two charges, not season variants`() {
        val o = offer(row("Preparation fee", "440", extrasId = 4), row("Preparation Fee", "60", extrasId = 4))
        assertEquals(2, o.filterDuplicateExtras().size)
    }

    @Test
    fun `optional rows keep the cheapest per key and hide behind an obligatory row`() {
        val o =
            offer(
                row("Skipper", "1400", obligatory = false, extrasId = 1),
                row("Skipper", "1600", obligatory = false, extrasId = 1),
                row("SUP", "150", obligatory = false, extrasId = 9),
                row("Transit log", "250", extrasId = 5),
                row("Transit log (extra)", "30", obligatory = false, extrasId = 5),
            )
        val result = o.filterDuplicateExtras()
        val skipper = result.single { it.first.name == "Skipper" }
        assertEquals(BigDecimal("1400"), skipper.first.price)
        assertTrue(skipper.second, "dearer skipper variant exists")
        assertEquals(setOf("Skipper", "SUP", "Transit log"), result.map { it.first.name }.toSet())
    }

    @Test
    fun `season stem ignores only the season qualifier`() {
        assertEquals(Offer.seasonVariantStem("Nineteen APA / High season (30%)"), Offer.seasonVariantStem("Nineteen APA/ Low season II  (30%)"))
        assertTrue(Offer.seasonVariantStem("APA 30%") != Offer.seasonVariantStem("APA 40%"))
        assertTrue(Offer.seasonVariantStem("Skipper") != Offer.seasonVariantStem("Skipper's liability insurance"))
    }

    private fun calc(
        name: String,
        price: String,
        extrasId: Long?,
        externalId: Long?,
        obligatory: Boolean = true,
    ) = InternalCalcDto(
        id = nextId++,
        name = name,
        labelCode = null,
        unitPriceEur = BigDecimal(price),
        unit = ExtrasUnitType.PER_BOOKING,
        obligatory = obligatory,
        payableInBase = false,
        offerPriceEur = BigDecimal(price),
        offerUnit = ExtrasUnitType.PER_BOOKING,
        calcPriceEur = null,
        extrasId = extrasId,
        externalId = externalId,
        isStartingPrice = null,
        paymentType = ExtraPaymentType.WITH_BOOKING,
    )

    @Test
    fun `second obligatory offer row under the same key does not overwrite the first`() {
        val merged =
            mergeYachtAndOfferExtras(
                emptyList(),
                listOf(calc("Skipper", "2940", 1, 11), calc("Skipper's liability insurance", "350", 1, 12)),
            )
        assertEquals(listOf("Skipper", "Skipper's liability insurance"), merged.map { it.name })
    }

    @Test
    fun `offer rows still replace the yacht rows they duplicate`() {
        val merged =
            mergeYachtAndOfferExtras(
                // Comfort Pack: same partner id, renamed; Handling fee: same catalogue key, other id.
                listOf(calc("Comfort Pack", "500", 7, 70), calc("Handling fee", "375", 3, 30)),
                listOf(calc("Comfort Package", "500", null, 70), calc("Handling fee", "375", 3, 99)),
            )
        assertEquals(listOf("Comfort Package", "Handling fee"), merged.map { it.name })
        assertEquals(2, merged.size)
    }

    @Test
    fun `a second offer row never takes an unrelated yacht row that only shares its id`() {
        val merged =
            mergeYachtAndOfferExtras(
                // Yacht "Tourist tax" happens to carry id 12 from another id space.
                listOf(calc("Tourist tax", "20", 8, 12)),
                listOf(calc("Skipper", "2940", 1, 11), calc("Skipper's liability insurance", "350", 1, 12)),
            )
        assertEquals(listOf("Tourist tax", "Skipper", "Skipper's liability insurance"), merged.map { it.name })
    }

    @Test
    fun `an optional offer row still replaces the row with its key`() {
        val merged =
            mergeYachtAndOfferExtras(
                listOf(calc("SUP", "100", 9, 90, obligatory = false)),
                listOf(calc("SUP", "150", 9, 91, obligatory = false)),
            )
        assertEquals(listOf(BigDecimal("150")), merged.map { it.offerPriceEur })
    }
}
