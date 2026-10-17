package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetail
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.ModelDonorLookup
import hr.workspace.boat4you.domains.catalouge.services.YachtGalleryService
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal

/**
 * The boat page of a partner boat with our own media (V9_92, owner request 17.10.2026, gulet Allure 11769): our photos
 * first, as the boat's own photos (kind PARTNER - the sites need no change for them), the admin's main as the one main,
 * the film and the absolute crew profile URL at the top level.
 */
class YachtMapperOwnMediaTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private val galleryOn =
        YachtGalleryService(
            RealPriceSwitchesFixture.switches(mapOf(SettingsKeyEnum.GALLERY_EXTENDED to "ON")),
            mock(ModelDonorLookup::class.java),
            mock(CatalogueImageRepository::class.java),
        )
    private val mapper =
        YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer()), galleryOn, "https://api.boat4you.com/")

    private fun image(
        id: Long,
        position: Int,
        own: Boolean,
        main: Boolean = false,
        externalUrl: String? = null,
    ) = YachtImage().apply {
        this.id = id
        url = "y-11769/$id.webp"
        this.externalUrl = externalUrl
        this.position = position.toShort()
        mainImage = main
        source = if (own) YachtImageSource.OWN else YachtImageSource.PARTNER
    }

    private fun allure() =
        Yacht().apply {
            id = 11769L
            name = "Allure"
            entryType = EntryType.EXTERNAL
            model = Model().apply { name = "Gulet" }
            yachtImages =
                mutableSetOf(
                    image(200130, 4, own = false, externalUrl = "https://www.booking-manager.com/cbm/documents/allure_layout.jpg"),
                    image(200134, 5, own = false, main = true, externalUrl = "https://www.booking-manager.com/cbm/documents/allure.jpg"),
                    image(900, 0, own = true),
                    image(901, 1, own = true),
                )
            mainImageId = 900L
            videoUrl = "https://vimeo.com/982130882"
            crewProfileUrl = "documents/y-11769/0c8f.pdf"
        }

    @Test
    fun `own photos first as the boat's own photos, the admin's main the one main, film and crew profile on top`() {
        val dto = mapper.toDetailsDto(allure(), emptyList(), emptyList(), null, LanguageEnum.EN)

        dto.yachtImages!!.map { Triple(it.id, it.kind, it.mainImage) } shouldContainExactly
            listOf(
                Triple(900L, YachtImageDto.KIND_PARTNER, true),
                Triple(901L, YachtImageDto.KIND_PARTNER, false),
                Triple(200130L, YachtImageDto.KIND_LAYOUT, false),
                Triple(200134L, YachtImageDto.KIND_PARTNER, false), // the partner's flag yields to the admin's main
            )
        dto.videoUrl shouldBe "https://vimeo.com/982130882"
        dto.crewProfileUrl shouldBe "https://api.boat4you.com/public/yachts/11769/crew-profile.pdf"
    }

    @Test
    fun `no own media - both fields null - a custom boat's film comes from its custom details`() {
        val plain =
            allure().apply {
                videoUrl = null
                crewProfileUrl = null
            }
        mapper.toDetailsDto(plain, emptyList(), emptyList(), null, LanguageEnum.EN).let {
            it.videoUrl shouldBe null
            it.crewProfileUrl shouldBe null
        }

        val custom =
            plain.apply {
                entryType = EntryType.CUSTOM
                customYachtDetails =
                    mutableSetOf(
                        CustomYachtDetail().apply {
                            lowPrice = BigDecimal("12000")
                            videoUrl = "https://youtu.be/dQw4w9WgXcQ"
                            country =
                                Country().apply {
                                    id = 1
                                    name = "Croatia"
                                    code2 = "HR"
                                }
                        },
                    )
            }
        mapper.toDetailsDto(custom, emptyList(), emptyList(), null, LanguageEnum.EN).videoUrl shouldBe "https://youtu.be/dQw4w9WgXcQ"
        mapper.toDetailsDto(custom.apply { videoUrl = "https://vimeo.com/1" }, emptyList(), emptyList(), null, LanguageEnum.EN).videoUrl shouldBe
            "https://vimeo.com/1" // ours first
    }
}
