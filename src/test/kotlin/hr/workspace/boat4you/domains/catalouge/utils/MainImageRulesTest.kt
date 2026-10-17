package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
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

    // V9_91, owner incident 16.10.2026 (FuFu 4345): NauSys flags its MODEL picture mainPicture, and that picture is the
    // generic "Picture is not shown due to possible legal issues" placeholder for some models.
    private val nausysModel = "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg"
    private val nausysYacht = "https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/"

    private fun blocked(id: Long) =
        image(id, "x", 0, main = true).apply {
            externalUrl = nausysModel
            hidden = true
        }

    @Test
    fun `a hidden partner main is never picked - the photo with the lowest position`() {
        val n2 = image(341900, "x", 2).apply { externalUrl = nausysYacht + "n2.jpg" }
        val n3 = image(341901, "x", 3).apply { externalUrl = nausysYacht + "n3.jpg" }
        val layout = image(75723, "x", 1).apply { externalUrl = nausysYacht + "layout.png" }
        MainImageRules.pick(listOf(blocked(341899), layout, n3, n2), currentMainId = 341899) shouldBe n2
    }

    @Test
    fun `a boat whose every image is hidden has no main - not even the placeholder`() {
        MainImageRules.pick(listOf(blocked(1), blocked(2)), currentMainId = 1) shouldBe null
    }

    @Test
    fun `only a layout besides the placeholder - the layout, never the placeholder`() {
        val layout = image(5, "x", 1).apply { externalUrl = nausysYacht + "layout.png" }
        MainImageRules.pick(listOf(blocked(4), layout), currentMainId = 4) shouldBe layout
    }

    @Test
    fun `layout_override decides over the file name both ways`() {
        val plan = image(30, "main.png", 0, main = true).apply { layoutOverride = true } // a plan whose name says nothing
        val photo = image(31, "victoria_layout_3.jpg", 1).apply { layoutOverride = false } // an aerial photo named layout
        val other = image(32, "n2.jpg", 2)
        MainImageRules.isLayout(plan) shouldBe true
        MainImageRules.isLayout(photo) shouldBe false
        MainImageRules.pick(listOf(plan, photo, other), null) shouldBe photo
    }

    @Test
    fun `flagMain - exactly the picked image carries the flag, null clears all, changed rows returned`() {
        val a = image(40, "a.jpg", 0, main = true).apply { hidden = true }
        val b = image(41, "b.jpg", 1, main = null)
        val c = image(42, "c.jpg", 2, main = false)
        MainImageRules.flagMain(listOf(a, b, c), 41).map { it.id } shouldBe listOf(40L, 41L)
        listOf(a, b, c).map { it.mainImage } shouldBe listOf(false, true, false)
        MainImageRules.flagMain(listOf(a, b, c), 41) shouldBe emptyList()
        MainImageRules.flagMain(listOf(a, b, c), null).map { it.id } shouldBe listOf(41L)
    }

    @Test
    fun `V9_92 - an own photo as the current main beats the partner's main flag, a partner current main does not`() {
        val ownPhoto =
            image(50, "x", 3).apply {
                externalUrl = null
                url = "y-11769/own.webp"
                source = YachtImageSource.OWN
            }
        val partnerMain = image(51, "n1.jpg", 0, main = true)
        val partnerOther = image(52, "n2.jpg", 1)
        MainImageRules.pick(listOf(partnerMain, ownPhoto, partnerOther), currentMainId = 50) shouldBe ownPhoto
        // our photos alone do not take the card: the partner's main stays until an admin picks one of ours
        MainImageRules.pick(listOf(partnerMain, ownPhoto, partnerOther), currentMainId = 51) shouldBe partnerMain
        MainImageRules.pick(listOf(partnerMain, ownPhoto, partnerOther), currentMainId = 52) shouldBe partnerMain
        // a hidden own main gives the card back to the partner's main photo
        MainImageRules.pick(listOf(partnerMain, ownPhoto.apply { hidden = true }, partnerOther), currentMainId = 50) shouldBe partnerMain
    }

    @Test
    fun `V9_92 - partnerOffset puts the partner's pictures right after our last own photo, 0 without one`() {
        val own = { id: Long, position: Int -> image(id, "x", position).apply { source = YachtImageSource.OWN } }
        OwnImageRules.partnerOffset(listOf(image(1, "a.jpg", 7))) shouldBe 0
        OwnImageRules.partnerOffset(listOf(own(2, 0), own(3, 4), image(1, "a.jpg", 1))) shouldBe 5
        OwnImageRules.partnerImages(listOf(own(2, 0), image(1, "a.jpg", 1))).map { it.id } shouldBe listOf(1L)
    }
}
