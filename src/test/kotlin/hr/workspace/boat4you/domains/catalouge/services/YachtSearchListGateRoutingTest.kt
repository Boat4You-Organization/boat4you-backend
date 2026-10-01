package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import hr.workspace.boat4you.domains.catalouge.services.YachtSearchListGateRoutingTest.RecordingRunner.Routed
import jakarta.persistence.EntityManager
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 1.10.2026 (review of the Codex audit F2 fix): the admin Create Reservation modal searches
 * `/public/yachts` as SYSTEM_ADMIN, and a public burst must never answer it with 503 "Search is
 * busy". Admin listings take [HeavyQueryRunner.readUngated]; public ones the gated [HeavyQueryRunner.read].
 */
class YachtSearchListGateRoutingTest {
    /** Records which path the listing took, then stops before any SQL. */
    private class RecordingRunner : HeavyQueryRunner {
        class Routed(
            val path: String,
            val query: HeavyQuery,
        ) : RuntimeException()

        override fun <T> read(
            query: HeavyQuery,
            block: () -> T,
        ): T = throw Routed("gated", query)

        override fun <T> readUngated(
            query: HeavyQuery,
            block: () -> T,
        ): T = throw Routed("ungated", query)
    }

    private val service =
        YachtQueryingService(
            mock(EntityManager::class.java),
            mock(YachtRepository::class.java),
            mock(LocationRepository::class.java),
            mock(ExternalReservationRepository::class.java),
            mock(YachtMapper::class.java),
            mock(OfferRepository::class.java),
            mock(CustomYachtViewRepository::class.java),
            mock(CustomYachtDetailRepository::class.java),
            mock(YachtTranslationRepository::class.java),
            mock(OfferMapper::class.java),
            mock(FileSystemService::class.java),
            mock(ExchangeRateCalculationService::class.java),
            mock(YachtExtraRepository::class.java),
            mock(ExternalBaseRepository::class.java),
            mock(RegionRepository::class.java),
            mock(CountryRepository::class.java),
            RecordingRunner(),
        )

    private fun routeOf(isAdmin: Boolean): Routed =
        assertFailsWith<Routed> {
            service.getYachts(mock(YachtSearchParamObject::class.java), null, LanguageEnum.EN, 0, 18, isAdmin)
        }

    @Test
    fun `a public listing goes through the gate`() {
        val routed = routeOf(isAdmin = false)
        assertEquals("gated", routed.path)
        assertEquals(HeavyQuery.SEARCH_LIST, routed.query)
    }

    @Test
    fun `an admin listing skips the gate`() {
        val routed = routeOf(isAdmin = true)
        assertEquals("ungated", routed.path)
        assertEquals(HeavyQuery.SEARCH_LIST, routed.query)
    }
}
