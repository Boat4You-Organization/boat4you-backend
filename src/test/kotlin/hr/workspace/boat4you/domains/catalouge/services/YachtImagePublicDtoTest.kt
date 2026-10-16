package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The public image list (boat page, reservation detail) - owner incident 16.10.2026, FuFu 4345: the NauSys model
 * picture flagged mainPicture was the partner's "Picture is not shown due to possible legal issues" placeholder and the
 * page showed it as the hero. Hidden images (V9_91) are never in the list, and exactly one image is the main one: the
 * card photo's rule (MainImageRules.pick), so hero, share card, PDF cover and search card agree.
 */
class YachtImagePublicDtoTest {
    private val pictures = "https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/"

    private fun image(
        id: Long,
        name: String,
        position: Int,
        main: Boolean? = null,
        hidden: Boolean = false,
    ) = YachtImage().apply {
        this.id = id
        externalUrl = if (name.startsWith("http")) name else pictures + name
        url = "y-4345/$id.webp"
        this.position = position.toShort()
        mainImage = main
        this.hidden = hidden
    }

    private val placeholder =
        image(341899, "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg", 0, main = true, hidden = true)
    private val layout = image(75723, "layout.png", 1, main = false)
    private val n2 = image(341900, "n2.jpg", 2, main = false)
    private val n3 = image(341901, "n3.jpg", 3)

    @Test
    fun `the hidden placeholder is left out and the first photo is the one main image`() {
        val dtos = listOf(n3, placeholder, n2, layout).toPublicDtos(currentMainId = 341899)

        dtos.map { it.id } shouldContainExactly listOf(75723L, 341900L, 341901L)
        dtos.filter { it.mainImage == true }.map { it.id } shouldContainExactly listOf(341900L)
        dtos.single { it.id == 75723L }.layout shouldBe true
    }

    @Test
    fun `the card photo leads when the partner flags no photo - hero and card agree`() {
        val dtos = listOf(layout, n2, n3).toPublicDtos(currentMainId = 341901)

        dtos.filter { it.mainImage == true }.map { it.id } shouldContainExactly listOf(341901L)
        dtos.single { it.id == 341900L }.mainImage shouldBe false // stored false stays false
        n3.mainImage shouldBe null // the entity is not touched
    }

    @Test
    fun `a partner main photo stays the main, flags it does not carry stay as stored`() {
        val main = image(1, "a.jpg", 0, main = true)
        val other = image(2, "b.jpg", 1)
        val dtos = listOf(other, main).toPublicDtos(currentMainId = 2)

        dtos.map { it.mainImage } shouldContainExactly listOf(true, null)
    }

    @Test
    fun `a layout the partner flags main gives the flag to the photo`() {
        val plan = image(10, "layout.jpg", 0, main = true)
        val photo = image(11, "main.jpg", 1)
        listOf(plan, photo).toPublicDtos(null).map { it.mainImage } shouldContainExactly listOf(false, true)
    }

    @Test
    fun `a plan whose file name says nothing - layout_override moves it out of the photos`() {
        val plan = image(75797, "main.png", 0, main = true).apply { layoutOverride = true }
        val photo = image(75794, "n1.jpg", 1)
        val dtos = listOf(plan, photo).toPublicDtos(null)

        dtos.map { it.layout } shouldContainExactly listOf(true, false)
        dtos.map { it.mainImage } shouldContainExactly listOf(false, true)
    }

    @Test
    fun `only the placeholder - an empty list`() {
        listOf(placeholder).toPublicDtos(341899) shouldBe emptyList()
    }
}
