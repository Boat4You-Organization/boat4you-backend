package hr.workspace.boat4you.domains.catalouge.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.ModelDonorLookup
import hr.workspace.boat4you.domains.catalouge.services.YachtGalleryFixture
import hr.workspace.boat4you.domains.catalouge.services.YachtGalleryService
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * The gallery merge on the boat page (YachtMapper.toDetailsDto, photos DESIGN.md): OFF keeps today's image JSON byte
 * for byte; ON looks the marina up by the page's location, which is the period location of a dated request.
 */
class YachtMapperGalleryTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private val json: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    private fun mapper(gallery: YachtGalleryService) = YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer()), gallery)

    private val yacht =
        Yacht().apply {
            id = 11681L
            name = "Masterpiece"
            entryType = EntryType.EXTERNAL
            model =
                Model().apply {
                    id = 812L
                    name = "Lagoon 42"
                    manufacturer = Manufacturer().apply { name = "Lagoon" }
                }
            location =
                Location().apply {
                    id = 63L
                    name = "Marina Kaštela"
                    countryCode = "HR"
                }
            yachtImages =
                mutableSetOf(
                    YachtImage().apply {
                        id = 2L
                        url = "y-11681/b.webp"
                        externalUrl = "https://partner.example/l42_layout.jpg"
                        position = 1
                    },
                    YachtImage().apply {
                        id = 1L
                        url = "y-11681/a.webp"
                        externalUrl = "https://partner.example/main.jpg"
                        position = 0
                        mainImage = true
                    },
                    // not downloaded yet: never in the gallery
                    YachtImage().apply {
                        id = 3L
                        externalUrl = "https://partner.example/later.jpg"
                        position = 2
                    },
                )
        }

    @Test
    fun `switch OFF - the image JSON is today's, byte for byte`() {
        val images = mapper(YachtGalleryFixture.off()).toDetailsDto(yacht, emptyList(), emptyList(), null, LanguageEnum.EN).yachtImages

        json.writeValueAsString(images) shouldBe
            """[{"id":1,"position":0,"mainImage":true,"layout":false},{"id":2,"position":1,"mainImage":null,"layout":true}]"""
    }

    @Test
    fun `switch ON - kinds on the own images, marina photos of the period location and its same-place pair`() {
        val photos = mock(CatalogueImageRepository::class.java)
        `when`(photos.findSamePlacePartnerIds(1935L)).thenReturn(listOf(63L))
        `when`(photos.findActiveByLocationIds(listOf(1935L, 63L))).thenReturn(
            listOf(
                CatalogueImage().apply {
                    id = 2_000_000_007L
                    locationId = 63L
                    url = "marina-63/x.webp"
                    credit = "Foto: Marina Kaštela"
                    sourceUrl = "https://example.org/gallery"
                },
            ),
        )
        val gallery =
            YachtGalleryService(
                RealPriceSwitchesFixture.switches(mapOf(SettingsKeyEnum.GALLERY_EXTENDED to "ON")),
                mock(ModelDonorLookup::class.java),
                photos,
            )
        val period = LocationDto(id = "l-1935", name = "Marina Kaštela | Kaštel Gomilica", countryCode = "HR")

        val images = mapper(gallery).toDetailsDto(yacht, emptyList(), emptyList(), null, LanguageEnum.EN, period).yachtImages!!

        images.map { it.id to it.kind } shouldContainExactly
            listOf(1L to YachtImageDto.KIND_PARTNER, 2L to YachtImageDto.KIND_LAYOUT, 2_000_000_007L to YachtImageDto.KIND_MARINA)
        json.writeValueAsString(images.last()) shouldBe
            """{"id":2000000007,"position":2000,"mainImage":false,"layout":false,"kind":"MARINA",""" +
            """"caption":"Marina Kaštela | Kaštel Gomilica","credit":"Foto: Marina Kaštela","sourceUrl":"https://example.org/gallery"}"""
        json.writeValueAsString(images.first()) shouldBe """{"id":1,"position":0,"mainImage":true,"layout":false,"kind":"PARTNER"}"""
    }
}
