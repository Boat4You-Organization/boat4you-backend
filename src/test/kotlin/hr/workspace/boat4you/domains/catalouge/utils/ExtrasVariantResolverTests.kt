package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * LUNA Lagoon 46 (yacht 9270, MMK / Cata Sailing, 23.8.2026): three obligatory
 * yacht-level Comfort Packs (1 / 2 / 3 weeks), the one-week offer carries only
 * the 600 € one. Only the two siblings may be superseded — nothing else.
 */
class ExtrasVariantResolverTests {
    private fun yachtExtra(
        name: String,
        obligatory: Boolean = true,
        externalId: Long? = null,
        validDaysFrom: Int? = null,
        validDaysTo: Int? = null,
    ) = YachtExtra().apply {
        this.name = name
        this.obligatory = obligatory
        this.externalId = externalId
        this.price = BigDecimal.TEN
        this.validDaysFrom = validDaysFrom
        this.validDaysTo = validDaysTo
    }

    private fun offerExtra(
        name: String,
        obligatory: Boolean = true,
        externalId: Long? = null,
    ) = OfferExtra().apply {
        this.name = name
        this.obligatory = obligatory
        this.externalId = externalId
        this.price = BigDecimal.TEN
    }

    private val pack1 = "Comfort Pack  (Final Cleaning, Transit Log, Starter Pack, Bed Linens, Towels, Beach Towels, Wifi Unlimited) "
    private val pack2 = "Comfort Pack 2 weeks (Final Cleaning, Transit Log, Starter Pack, Bed Linens, Towels, Beach Towels, Wifi Unlimited) "
    private val pack3 = "Comfort Pack 3 weeks  (Final Cleaning, Transit Log, Starter Pack, Bed Linens, Towels, Beach Towels, Wifi Unlimited) "

    @Test
    fun `normalizes duration, digits and parentheticals away`() {
        assertEquals("comfort pack", ExtrasVariantResolver.normalizeVariantName(pack1))
        assertEquals("comfort pack", ExtrasVariantResolver.normalizeVariantName(pack2))
        assertEquals("comfort pack", ExtrasVariantResolver.normalizeVariantName(pack3))
        assertEquals("damage waiver - refundable amount €", ExtrasVariantResolver.normalizeVariantName("2 weeks Damage Waiver (SY 44'-48') - refundable amount 650€"))
        assertEquals("national parks permit", ExtrasVariantResolver.normalizeVariantName("National Parks Permit (up to 6)"))
        assertEquals("transit log", ExtrasVariantResolver.normalizeVariantName("Transit Log 2026  2 weeks"))
        assertEquals("", ExtrasVariantResolver.normalizeVariantName(null))
    }

    @Test
    fun `one-week offer supersedes the 2 and 3 week packs only`() {
        val yachtRows = listOf(
            yachtExtra(pack1, externalId = 7198840844403015),
            yachtExtra(pack2, externalId = 1375193030000103015),
            yachtExtra(pack3, externalId = 1375208570000103015),
            yachtExtra("Visitor tax per person", externalId = 799948200000103015),
            yachtExtra("Transit log"), // obligatory, no twin on the offer -> must stay
            yachtExtra("Adrenaline package (High field 5,4 + 100 HP)", obligatory = false),
        )
        val offerRows = listOf(
            offerExtra(pack1, externalId = 7198840844403015),
            offerExtra("Visitor tax per person", externalId = 799948200000103015),
        )

        val superseded = ExtrasVariantResolver.supersededYachtExtraKeys(offerRows, yachtRows)

        assertEquals(setOf(pack2, pack3), superseded)
    }

    @Test
    fun `two-week offer keeps the 2 week pack and drops its siblings`() {
        val yachtRows = listOf(yachtExtra(pack1), yachtExtra(pack2), yachtExtra(pack3))
        val offerRows = listOf(offerExtra(pack2))

        assertEquals(setOf(pack1, pack3), ExtrasVariantResolver.supersededYachtExtraKeys(offerRows, yachtRows))
    }

    @Test
    fun `matches on externalId or catalogue key even when the partner renamed the row`() {
        // NOTE: extrasId is an insertable=false mirror column — setting `extras`
        // does NOT populate it in memory, so the test sets it explicitly the way
        // Hibernate hydrates it.
        val yachtRows = listOf(
            yachtExtra("Comfort Pack 39/40/42 (incl. OB)", externalId = 55),
            yachtExtra("Comfort pack deluxe").apply { extrasId = 10 },
        )
        val offerRows = listOf(
            offerExtra("Comfort Pack 38/39/40/42", externalId = 55), // same row, renamed
            offerExtra("Comfort pack").apply { extrasId = 10 }, // same catalogue extra, key "10"
        )

        assertEquals(emptySet(), ExtrasVariantResolver.supersededYachtExtraKeys(offerRows, yachtRows))
    }

    @Test
    fun `reservation snapshot anchors work like offer rows`() {
        val yachtRows = listOf(yachtExtra(pack1), yachtExtra(pack2), yachtExtra(pack3), yachtExtra("Transit log"))
        val booked = ReservationExtra().apply {
            name = pack1
            obligatory = true
            yachtExtrasKey = pack1
        }
        val optionalBooked = ReservationExtra().apply {
            name = pack2
            obligatory = false
            yachtExtrasKey = pack2
        }

        assertEquals(
            setOf(pack2, pack3),
            ExtrasVariantResolver.supersededByReservation(listOf(booked, optionalBooked), yachtRows),
        )
        assertEquals(emptySet(), ExtrasVariantResolver.supersededByReservation(emptyList(), yachtRows))
    }

    @Test
    fun `case-only twin is superseded by the offer row`() {
        // "Comfort pack" (yacht) vs "Comfort Pack" (offer) — different extrasKey, same charge.
        val yachtRows = listOf(yachtExtra("Comfort pack"))
        val offerRows = listOf(offerExtra("Comfort Pack"))

        assertEquals(setOf("Comfort pack"), ExtrasVariantResolver.supersededYachtExtraKeys(offerRows, yachtRows))
    }

    @Test
    fun `offers without obligatory rows and optional twins change nothing`() {
        val yachtRows = listOf(yachtExtra(pack2), yachtExtra(pack3))

        assertEquals(emptySet(), ExtrasVariantResolver.supersededYachtExtraKeys(emptyList(), yachtRows))
        assertEquals(emptySet(), ExtrasVariantResolver.supersededYachtExtraKeys(listOf(offerExtra(pack1, obligatory = false)), yachtRows))
        // optional yacht rows are never touched, whatever the offer says
        assertEquals(
            emptySet(),
            ExtrasVariantResolver.supersededYachtExtraKeys(listOf(offerExtra(pack1)), listOf(yachtExtra(pack2, obligatory = false))),
        )
    }

    // Sailing Sicily Bali 5.4 (Therasia 11907, 22.10.2026): MMK bounds every row to charter lengths in days -
    // the weekly pack 0-7, "Luxury Pack 2weeks" 8-14, the 0 EUR label row 0-365; APA season variants likewise.
    private val luxuryWeek = "Luxury Pack Bali 5.4 (Skipper-Hostess- Cook-final cleaning - bed linen + towels- beach towels)"
    private val luxuryTwoWeeks = "Luxury Pack 2weeks (Skipper-Hostess- Cook-final cleaning - bed linen + towels- beach towels)"
    private val luxuryLabel = "Luxury Pack Bali 5.4 5200€ per week (Skipper-Hostess- Cook-final cleaning - bed linen + towels- beach towels)"

    @Test
    fun `a glued duration token normalizes away like a spaced one`() {
        assertEquals("luxury pack", ExtrasVariantResolver.normalizeVariantName(luxuryTwoWeeks))
        assertEquals("luxury pack", ExtrasVariantResolver.normalizeVariantName("Luxury Pack 2 weeks (…)"))
    }

    @Test
    fun `charter-length bounds decide the variant without looking at the name`() {
        val rows = listOf(
            yachtExtra(luxuryWeek, externalId = 34832401335801230, validDaysFrom = 0, validDaysTo = 7),
            yachtExtra(luxuryTwoWeeks, externalId = 36160021335801230, validDaysFrom = 8, validDaysTo = 14),
            yachtExtra(luxuryLabel, externalId = 36186611335801230, validDaysFrom = 0, validDaysTo = 365),
            yachtExtra("APA Bali 5.4 20% charter price  Aug-Sept", externalId = 35544411335801230, validDaysFrom = 0, validDaysTo = 7),
            yachtExtra("APA Bali 5.4 20% charter price  Aug-Sept 2 weeks", externalId = 35621161335801230, validDaysFrom = 8, validDaysTo = 14),
            yachtExtra("Transit log"), // no bounds synced yet -> never excluded
            yachtExtra("Safety net", obligatory = false, validDaysFrom = 8, validDaysTo = 14), // optional rows are not touched
        )

        assertEquals(
            setOf(rows[1].extrasKey(), rows[4].extrasKey()),
            ExtrasVariantResolver.outOfDurationYachtExtraKeys(rows, nights = 7),
        )
        assertEquals(
            setOf(rows[0].extrasKey(), rows[3].extrasKey()),
            ExtrasVariantResolver.outOfDurationYachtExtraKeys(rows, nights = 14),
        )
        assertEquals(emptySet(), ExtrasVariantResolver.outOfDurationYachtExtraKeys(listOf(rows[5], rows[6]), nights = 7))
    }

    @Test
    fun `a zero or missing upper bound never excludes`() {
        val open = yachtExtra("Tourist tax", validDaysFrom = 0, validDaysTo = 0)
        val unknown = yachtExtra("Transit log")
        kotlin.test.assertTrue(open.appliesToNights(21))
        kotlin.test.assertTrue(unknown.appliesToNights(21))
        kotlin.test.assertFalse(yachtExtra("Two weeks only", validDaysFrom = 8, validDaysTo = 14).appliesToNights(7))
    }
}
