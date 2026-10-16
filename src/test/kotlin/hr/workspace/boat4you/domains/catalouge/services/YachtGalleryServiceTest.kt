package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * The boat page gallery rules (photos DESIGN.md, Mario 16.10.2026): with GALLERY_EXTENDED=OFF the boat's own list comes
 * back untouched; ON, a boat without one real photo gets up to 8 MODEL_SAMPLE photos (the first one main), a boat
 * with a real photo never does, and the departure marina adds at most 3 MARINA photos, last and never main.
 */
class YachtGalleryServiceTest {
    private val donors: ModelDonorLookup = mock(ModelDonorLookup::class.java)
    private val photos: CatalogueImageRepository = mock(CatalogueImageRepository::class.java)

    private fun gallery(on: Boolean) =
        YachtGalleryService(
            RealPriceSwitchesFixture.switches(mapOf(SettingsKeyEnum.GALLERY_EXTENDED to if (on) "ON" else "OFF")),
            donors,
            photos,
        )

    private val kastela = LocationDto(id = "l-63", name = "Marina Kaštela | Kaštel Gomilica", countryCode = "HR")

    private val home =
        Location().apply {
            id = 63L
            countryCode = "HR"
        }

    private fun yacht(
        modelName: String = "Lagoon 42",
        builder: Boolean = true,
    ): Yacht {
        val boatModel =
            Model().apply {
                id = 812L
                name = modelName
                manufacturer = if (builder) Manufacturer().apply { name = "Lagoon" } else null
            }
        return Yacht().apply {
            id = 10L
            model = boatModel
            location = home
        }
    }

    private fun own(
        id: Long,
        position: Int,
        layout: Boolean = false,
        main: Boolean? = null,
    ) = YachtImageDto(id = id, position = position.toShort(), mainImage = main, layout = layout)

    private fun marina(
        id: Long,
        caption: String? = null,
    ) = CatalogueImage().apply {
        this.id = id
        locationId = 63L
        url = "marina-63/$id.webp"
        this.caption = caption
        credit = "Foto: Marina Kaštela"
        sourceUrl = "https://example.org/gallery"
    }

    private fun donorWith(vararg imageIds: Long) {
        `when`(donors.donorFor(812L, "HR")).thenReturn(ModelDonor(20L))
        `when`(donors.donorPhotoIds(20L)).thenReturn(imageIds.toList())
    }

    private fun marinaPhotos(vararg rows: CatalogueImage) {
        `when`(photos.findSamePlacePartnerIds(63L)).thenReturn(listOf(1935L))
        `when`(photos.findActiveByLocationIds(listOf(63L, 1935L))).thenReturn(rows.toList())
    }

    @Test
    fun `switch OFF - the boat's own list, untouched, and no lookup at all`() {
        val own = listOf(own(1, 0, layout = true, main = true))

        gallery(on = false).extend(yacht(), own, kastela) shouldBeSameInstanceAs own
        gallery(on = false).extend(yacht(), emptyList(), kastela).shouldBeEmpty()

        verifyNoInteractions(donors, photos)
    }

    @Test
    fun `no real photo - MODEL_SAMPLE photos of the donor, the first one main, positions from 1000, at most 8`() {
        donorWith(*LongArray(10) { 500L + it })

        val images = gallery(on = true).extend(yacht(), listOf(own(1, 0, layout = true, main = true)), null)

        images.first() shouldBe own(1, 0, layout = true, main = false).copy(kind = YachtImageDto.KIND_LAYOUT)
        val samples = images.drop(1)
        samples.map { it.id } shouldContainExactly (500L..507L).toList()
        samples.map { it.kind }.toSet() shouldBe setOf(YachtImageDto.KIND_MODEL_SAMPLE)
        samples.map { it.position?.toInt() } shouldContainExactly (1000..1007).toList()
        samples.map { it.mainImage } shouldContainExactly listOf(true) + List(7) { false }
        samples.all { !it.layout && it.caption == null && it.credit == null && it.sourceUrl == null } shouldBe true
        images.count { it.mainImage == true } shouldBe 1
    }

    @Test
    fun `no image at all - MODEL_SAMPLE photos too`() {
        donorWith(500L, 501L, 502L, 503L)

        val images = gallery(on = true).extend(yacht(), emptyList(), null)

        images.map { it.id } shouldContainExactly listOf(500L, 501L, 502L, 503L)
        images.first().mainImage shouldBe true
    }

    @Test
    fun `a real photo - no MODEL_SAMPLE and no donor lookup, own images tagged PARTNER or LAYOUT`() {
        val own = listOf(own(1, 0, main = true), own(2, 1, layout = true))

        val images = gallery(on = true).extend(yacht(), own, null)

        images shouldContainExactly
            listOf(
                own(1, 0, main = true).copy(kind = YachtImageDto.KIND_PARTNER),
                own(2, 1, layout = true).copy(kind = YachtImageDto.KIND_LAYOUT),
            )
        verifyNoInteractions(donors)
    }

    @Test
    fun `a generic model or a model without a builder - no donor lookup`() {
        listOf("Custom", " custom made ", "Sailing yacht", "MOTORYACHT", "Motor sailer", "Motorboat").forEach { name ->
            gallery(on = true).extend(yacht(modelName = name), emptyList(), null).shouldBeEmpty()
        }
        gallery(on = true).extend(yacht(builder = false), emptyList(), null).shouldBeEmpty()
        verifyNoInteractions(donors)
    }

    @Test
    fun `no donor for the model - nothing added`() {
        `when`(donors.donorFor(812L, "HR")).thenReturn(ModelDonor(null))

        gallery(on = true).extend(yacht(), emptyList(), null).shouldBeEmpty()
        verify(donors, never()).donorPhotoIds(20L)
    }

    @Test
    fun `MARINA photos - at most 3, last, never main, positions from 2000, caption credit and source`() {
        marinaPhotos(marina(2_000_000_001L), marina(2_000_000_002L, caption = "South pier"), marina(2_000_000_003L), marina(2_000_000_004L))

        val images = gallery(on = true).extend(yacht(), listOf(own(1, 0, main = true), own(2, 1)), kastela)

        images.map { it.kind } shouldContainExactly
            listOf(YachtImageDto.KIND_PARTNER, YachtImageDto.KIND_PARTNER) + List(3) { YachtImageDto.KIND_MARINA }
        val marinas = images.drop(2)
        marinas.map { it.id } shouldContainExactly listOf(2_000_000_001L, 2_000_000_002L, 2_000_000_003L)
        marinas.map { it.position?.toInt() } shouldContainExactly listOf(2000, 2001, 2002)
        marinas.map { it.mainImage } shouldContainExactly listOf(false, false, false)
        marinas.map { it.caption } shouldContainExactly listOf("Marina Kaštela | Kaštel Gomilica", "South pier", "Marina Kaštela | Kaštel Gomilica")
        marinas.map { it.credit }.toSet() shouldBe setOf("Foto: Marina Kaštela")
        marinas.map { it.sourceUrl }.toSet() shouldBe setOf("https://example.org/gallery")
    }

    @Test
    fun `MARINA photos come after the MODEL_SAMPLE ones and the main image stays a model photo`() {
        donorWith(500L, 501L, 502L, 503L)
        marinaPhotos(marina(2_000_000_001L))

        val images = gallery(on = true).extend(yacht(), emptyList(), kastela)

        images.map { it.id } shouldContainExactly listOf(500L, 501L, 502L, 503L, 2_000_000_001L)
        images.filter { it.mainImage == true }.map { it.id } shouldContainExactly listOf(500L)
    }

    @Test
    fun `no marina photo for a custom boat's country location or a missing location`() {
        gallery(on = true).extend(yacht(), listOf(own(1, 0)), LocationDto(id = "C-54", name = "Croatia", countryCode = "HR")).size shouldBe 1
        gallery(on = true).extend(yacht(), listOf(own(1, 0)), null).size shouldBe 1
        verifyNoInteractions(photos)
    }
}
