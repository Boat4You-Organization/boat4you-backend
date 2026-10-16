package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.util.Optional

/**
 * Owner rule 16.10.2026 on the boat page: its own slug and its twin canonical (yacht_listing_twin, audit B17) are the
 * frozen yacht.public_slug (V9_90) - a later rename or model change never moves either; a boat not frozen yet keeps
 * today's computed slug byte for byte. Real YachtMapper, repositories and EntityManager mocked.
 */
class YachtPublicSlugReadTest {
    private fun boat(
        id: Long,
        name: String,
        frozen: String?,
    ) = Yacht().apply {
        this.id = id
        this.name = name
        publicSlug = frozen
        sysActive = true
        entryType = EntryType.EXTERNAL
        vesselType = VesselType.CATAMARAN
        agency = Agency().apply { active = true }
        model = Model().apply {
            this.name = "Lagoon 42"
            manufacturer = Manufacturer().also { it.name = "Lagoon" }
        }
    }

    // the page is a second listing of the boat; the listings show 4066
    private val page = boat(11681, "Masterpiece", "lagoon-42-masterpiece-11681")
    private val canonical = boat(4066, "Masterpiece", "lagoon-42-masterpiece-4066")

    // yacht_listing_twin -> 4066; hasBookableFutureOffer -> true
    private val query: Query =
        mock(Query::class.java) { inv ->
            when (inv.method.name) {
                "setParameter" -> inv.mock
                "getResultList" -> listOf(canonical.id)
                "getSingleResult" -> true
                else -> null
            }
        }
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)

    private val service =
        YachtQueryingService(
            mock(EntityManager::class.java) { inv -> if (inv.method.name == "createNativeQuery") query else null },
            mock(YachtRepository::class.java) { inv ->
                if (inv.method.name == "findById") {
                    Optional.ofNullable(listOf(page, canonical).find { it.id == inv.arguments[0] })
                } else {
                    Mockito.RETURNS_DEFAULTS.answer(inv)
                }
            },
            mock(LocationRepository::class.java),
            mock(ExternalReservationRepository::class.java),
            YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer())),
            mock(OfferRepository::class.java),
            mock(CustomYachtViewRepository::class.java),
            mock(CustomYachtDetailRepository::class.java),
            mock(YachtTranslationRepository::class.java),
            mock(OfferMapper::class.java),
            mock(FileSystemService::class.java),
            exchangeRates,
            mock(YachtExtraRepository::class.java),
            mock(ExternalBaseRepository::class.java),
            mock(RegionRepository::class.java),
            mock(CountryRepository::class.java),
            PassThroughHeavyQueries,
            SearchSwitchesTestSupport.defaults(),
            SearchSwitchesTestSupport.liveOptionLookup(),
        )

    private fun load() = service.getYacht(page.id!!, null, null, null, LanguageEnum.EN)

    @Test
    fun `the page and its twin canonical answer under the frozen slugs, whatever the names say now`() {
        load().slug shouldBe "lagoon-42-masterpiece-11681"
        load().listingCanonicalSlug shouldBe "lagoon-42-masterpiece-4066"

        // the partner renames both copies, R02 renames the model - the URLs stay
        page.name = "Masterpiece II"
        canonical.name = "Boat4You 42"
        canonical.model = Model().apply { name = "Lagoon 42 (2019)" }
        load().slug shouldBe "lagoon-42-masterpiece-11681"
        load().listingCanonicalSlug shouldBe "lagoon-42-masterpiece-4066"
        load().name shouldBe "Masterpiece II"
    }

    @Test
    fun `not frozen yet - today's computed slugs, byte for byte`() {
        page.publicSlug = null
        canonical.publicSlug = null
        page.name = "Masterpiece II"

        load().slug shouldBe "lagoon-42-masterpiece-ii-11681"
        load().listingCanonicalSlug shouldBe "lagoon-42-masterpiece-4066"
    }
}
