package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import jakarta.persistence.EntityManager
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 16.9.2026 cusma2 load incident, finding 2: `GET /public/yachts` WIDENED its query whenever the
 * `did` filter could not be resolved — `did=l-l-19` (the sister sites' double-prefix bug) returned
 * all 897 Croatian catamarans instead of the 2 boats in marina l-19, and `did=l-9999999` returned
 * all 13,609 yachts in 2.3 s. An asked-for destination that resolves to nothing must RESTRICT to
 * nothing, mirroring `YachtDistributionService.resolveDidScope`.
 *
 * Unit level on purpose: the predicate itself only runs against `yacht_search_view`, a materialised
 * view that a Testcontainers fixture would have to build and refresh for every case, while all the
 * behaviour under test lives in the token resolution feeding it. Plain Mockito, no Spring — only
 * the two lookup repositories matter here.
 */
class YachtSearchDidScopeTests {
    private val locationRepository: LocationRepository = mock(LocationRepository::class.java)
    private val countryRepository: CountryRepository = mock(CountryRepository::class.java)

    private val service =
        YachtQueryingService(
            mock(EntityManager::class.java),
            mock(YachtRepository::class.java),
            locationRepository,
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
            countryRepository,
        )

    private fun marina(
        id: Long,
        name: String,
        countryCode: String = "HR",
    ): Location =
        Location().apply {
            this.id = id
            this.name = name
            this.countryCode = countryCode
        }

    /** Mirrors the real lookup chain in `getMarinas`: findById, then the folded-name sibling ids,
     *  then the formula-safe re-fetch of those ids. */
    private fun stubMarina(
        id: Long,
        name: String,
        siblings: List<Long> = listOf(id),
    ) {
        `when`(locationRepository.findById(id)).thenReturn(Optional.of(marina(id, name)))
        `when`(locationRepository.findMarinaIdsByFoldedName(name, "HR")).thenReturn(siblings)
        `when`(locationRepository.findAllById(siblings)).thenReturn(siblings.map { marina(it, name) })
    }

    @Test
    fun `no did at all applies no location filter`() {
        val scope = service.resolveSearchDidScope(null)

        assertEquals(emptyList(), scope.marinaIds)
        assertEquals(emptyList(), scope.countryCodes)
        assertFalse(scope.matchesNothing, "no did must scan the whole catalogue, not zero rows")
    }

    @Test
    fun `an empty did list applies no location filter`() {
        val scope = service.resolveSearchDidScope(emptyList())

        assertEquals(emptyList(), scope.marinaIds)
        assertEquals(emptyList(), scope.countryCodes)
        assertFalse(scope.matchesNothing, "no token = no destination asked for")
    }

    @Test
    fun `blank did tokens restrict to zero rows, like the facet endpoints`() {
        // `did=,` reaches the controller as two blank entries. They resolve to no destination, so
        // they return zero rows rather than the 13,609-row catalogue scan — the same answer
        // `YachtDistributionService` / `YachtRelaxSuggestionService` already give for that URL
        // (a non-empty but unresolvable list becomes `AND FALSE`), so the sidebar counts and the
        // listing cannot disagree. Before the fix this URL was a 500 out of `"".first()`.
        listOf(listOf(""), listOf("", " ", "")).forEach { tokens ->
            val scope = service.resolveSearchDidScope(tokens)

            assertEquals(emptyList(), scope.marinaIds, "tokens=$tokens")
            assertEquals(emptyList(), scope.countryCodes, "tokens=$tokens")
            assertTrue(scope.matchesNothing, "blank did must not widen: tokens=$tokens")
        }
    }

    @Test
    fun `known marina resolves to its same-place siblings`() {
        stubMarina(19L, "Marina Kastela", siblings = listOf(19L, 4711L))

        val scope = service.resolveSearchDidScope(listOf("l-19"))

        assertEquals(listOf(19L, 4711L), scope.marinaIds)
        assertFalse(scope.matchesNothing)
    }

    @Test
    fun `known country resolves to its 2-letter code`() {
        `when`(countryRepository.findById(115L))
            .thenReturn(Optional.of(Country().apply { code2 = "hr" }))

        val scope = service.resolveSearchDidScope(listOf("c-115"))

        assertEquals(listOf("HR"), scope.countryCodes)
        assertEquals(emptyList(), scope.marinaIds)
        assertFalse(scope.matchesNothing)
    }

    @Test
    fun `unresolvable did tokens restrict to zero rows instead of widening`() {
        // The whole incident in one list: the sister sites' double prefix, an unknown marina id,
        // an unknown country id, an unknown prefix, and a token too short to even carry an id.
        // `findById` on an unstubbed mock returns Optional.empty(), which is exactly the
        // "unknown id" case.
        listOf("l-l-19", "l-9999999", "c-9999999", "x-1", "l", "r-", "19").forEach { token ->
            val scope = service.resolveSearchDidScope(listOf(token))

            assertEquals(emptyList(), scope.marinaIds, "token=$token")
            assertEquals(emptyList(), scope.countryCodes, "token=$token")
            assertTrue(scope.matchesNothing, "token=$token must return zero rows, not the catalogue")
        }
    }

    @Test
    fun `a valid token alongside an invalid one keeps the valid one and ignores the rest`() {
        stubMarina(19L, "Marina Kastela")

        val scope = service.resolveSearchDidScope(listOf("l-19", "l-l-19", "", "l-9999999"))

        assertEquals(listOf(19L), scope.marinaIds)
        assertFalse(scope.matchesNothing, "one resolvable token is still a real destination")
    }
}
