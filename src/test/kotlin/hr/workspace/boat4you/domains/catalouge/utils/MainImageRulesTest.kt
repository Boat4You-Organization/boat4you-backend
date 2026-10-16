package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 16.10.2026: 11 offered boats showed their deck plan on the search card although they have photos - the sync took the
 * partner's main / first image without asking whether it is the layout. The card photo is a photo whenever there is one.
 */
class MainImageRulesTest {
    private val mmk = "https://www.booking-manager.com/cbm/documents/"

    private fun image(
        id: Long,
        name: String,
        position: Int?,
        main: Boolean? = false,
    ) = YachtImage().apply {
        this.id = id
        externalUrl = mmk + name
        url = "y-12663/$id.webp"
        this.position = position?.toShort()
        mainImage = main
    }

    private val plan = image(1, "36397280967800260_1695203065343_L55_layout_5.jpg", 0, main = true)
    private val side = image(2, "36397340967800260_L55_vistalato.jpg", 3)
    private val deck = image(3, "37114131586300260_themoon_2071.jpg", 1)
    private val salon = image(4, "37114131586300261_themoon_salon.jpg", 2)

    @Test
    fun `a layout-named partner main is skipped - the photo with the lowest position`() {
        MainImageRules.pick(listOf(plan, side, deck, salon), null) shouldBe deck
        MainImageRules.pick(setOf(salon, side, plan, deck), currentMainId = plan.id) shouldBe deck // today's main = the plan
    }

    @Test
    fun `the partner main stays when it is a photo`() {
        MainImageRules.pick(listOf(plan, side.apply { mainImage = true }, deck), null) shouldBe side
    }

    @Test
    fun `without a partner main photo the current card photo stays - no main_image_id churn, no lastmod bump`() {
        MainImageRules.pick(listOf(plan, side, deck, salon), currentMainId = salon.id) shouldBe salon
    }

    @Test
    fun `a removed current main falls back to the lowest position`() {
        MainImageRules.pick(listOf(plan, side, salon), currentMainId = deck.id) shouldBe salon
    }

    @Test
    fun `a boat with only layouts keeps the old pick - the partner main, else the first image`() {
        val plan2 = image(5, "l42_plan.jpg", 1)
        MainImageRules.pick(listOf(plan2, plan), null) shouldBe plan
        MainImageRules.pick(listOf(plan2), null) shouldBe plan2
        MainImageRules.pick(emptyList(), 7) shouldBe null
    }

    @Test
    fun `a missing position sorts last, then the id decides`() {
        val a = image(10, "a.jpg", null)
        val b = image(11, "b.jpg", null)
        val c = image(12, "c.jpg", 5)
        MainImageRules.pick(listOf(b, a, c), null) shouldBe c
        MainImageRules.pick(listOf(b, a), null) shouldBe a
    }

    @Test
    fun `a NauSys layout flagged mainPicture is skipped as well`() {
        val nausys = "https://ws.nausys.com/CBMS-external/rest/yacht/103152/pictures/"
        val layout = image(20, "x", 0, main = true).apply { externalUrl = nausys + "layout.jpg" }
        val main = image(21, "x", 1).apply { externalUrl = nausys + "main.jpg" }
        MainImageRules.pick(listOf(layout, main), null) shouldBe main
    }
}
