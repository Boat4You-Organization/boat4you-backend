package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.services.OfferQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtTwinCanonicalService
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real-price rule (DESIGN.md WS-A): an admin search keeps the LEGACY predicate and no floor (legacyAvailability), and a
 * sitemap request (idFrom / idTo / sitemap=true) skips the floor (sitemapRequest); countryCodes alone does not, since
 * the sister sites send it on every customer listing.
 */
class YachtControllerLegacyAvailabilityTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val yachtQueryingService: YachtQueryingService = mock(YachtQueryingService::class.java)
    private val searched = mutableListOf<YachtSearchParamObject>()

    private val controller =
        YachtController(
            yachtQueryingService,
            mock(OfferQueryingService::class.java),
            mock(ExternalSyncService::class.java),
            mock(UserRepository::class.java),
            mock(YachtTwinCanonicalService::class.java),
            EquipmentAliases(mock(EquipmentRepository::class.java) { inv -> if (inv.method.name == "findAllByOrderByIdAsc") emptyList<Any>() else null }),
        )

    @BeforeEach
    fun stub() {
        SecurityContextHolder.clearContext()
        `when`(yachtQueryingService.getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { inv ->
            searched += inv.getArgument<YachtSearchParamObject>(0)
            PageImpl(emptyList<Any>())
        }
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun search(
        idFrom: Long? = null,
        countryCodes: List<String>? = null,
        sitemap: Boolean = false,
    ): YachtSearchParamObject {
        controller.getYachts(
            locations = listOf("c-54"),
            charterType = null,
            vesselType = null,
            manufacturer = null,
            model = null,
            mainSailType = null,
            minBuildYear = null,
            maxBuildYear = null,
            minPersons = null,
            maxPersons = null,
            minCabins = null,
            maxCabins = null,
            minBerths = null,
            maxBerths = null,
            minLength = null,
            maxLength = null,
            minPrice = null,
            maxPrice = null,
            startDate = null,
            endDate = null,
            minWc = null,
            maxWc = null,
            minEnginePower = null,
            maxEnginePower = null,
            curr = null,
            amenities = null,
            services = null,
            sortBy = null,
            yachtIds = null,
            agencyIds = null,
            includeUnavailable = false,
            countryCodes = countryCodes,
            idFrom = idFrom,
            idTo = idFrom?.plus(100),
            sitemap = sitemap,
            page = 0,
            size = 10,
            lang = null,
        )
        return searched.last()
    }

    @Test
    fun `a public search gets the switches, an admin search keeps the legacy predicate`() {
        assertEquals(false, search().legacyAvailability)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("admin", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
        assertEquals(true, search().legacyAvailability)
    }

    @Test
    fun `id range or sitemap=true mark a sitemap request, countryCodes alone does not`() {
        assertEquals(false, search().sitemapRequest)
        assertEquals(true, search(idFrom = 100).sitemapRequest)
        assertEquals(true, search(sitemap = true).sitemapRequest)
        assertEquals(true, search(countryCodes = listOf("HR"), sitemap = true).sitemapRequest)
        // A sister-site customer listing sends countryCodes on every call: the floor applies to it.
        assertEquals(false, search(countryCodes = listOf("HR")).sitemapRequest)
    }
}
