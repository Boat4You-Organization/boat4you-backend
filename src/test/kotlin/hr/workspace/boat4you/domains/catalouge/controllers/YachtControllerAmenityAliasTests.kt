package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.services.OfferQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtTwinCanonicalService
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An old (merged) equipment id in `/public/yachts?amenities=` is replaced by its canonical id BEFORE the search params
 * are built (equipment audit 8.10.2026), so `amenities=<refrigerator>` and `amenities=<fridge>` are the same search and
 * the same yachtSearchListCache key ({searchParams, ...}.toString()).
 */
class YachtControllerAmenityAliasTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val yachtQueryingService: YachtQueryingService = mock(YachtQueryingService::class.java)
    private val searched = mutableListOf<YachtSearchParamObject>()

    private val catalogue =
        listOf(14L to null, 29L to null, 90L to 14L, 65L to 23L, 23L to null).map { (id, mergedInto) ->
            Equipment().apply {
                this.id = id
                labelCode = "e$id"
                category = CategoryEnum.GALLEY
                matchKeys = ""
                mergedIntoId = mergedInto
            }
        }

    private val controller =
        YachtController(
            yachtQueryingService,
            mock(OfferQueryingService::class.java),
            mock(ExternalSyncService::class.java),
            mock(UserRepository::class.java),
            mock(YachtTwinCanonicalService::class.java),
            EquipmentAliases(mock(EquipmentRepository::class.java) { inv -> if (inv.method.name == "findAllByOrderByIdAsc") catalogue else null }),
        )

    @BeforeEach
    fun anonymousSearch() {
        SecurityContextHolder.clearContext()
        `when`(yachtQueryingService.getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { inv ->
            searched += inv.getArgument<YachtSearchParamObject>(0)
            PageImpl(emptyList<Any>())
        }
    }

    private fun search(amenities: List<Long>?) =
        controller.getYachts(
            locations = listOf("c-115"),
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
            amenities = amenities,
            services = null,
            sortBy = null,
            yachtIds = null,
            agencyIds = null,
            includeUnavailable = false,
            countryCodes = null,
            page = 0,
            size = 10,
            lang = null,
        )

    @Test
    fun `an alias id becomes its canonical id, duplicates drop, so the search and its cache key are the same`() {
        search(listOf(90L))
        search(listOf(14L))
        search(listOf(90L, 29L, 14L, 65L))
        search(null)

        assertEquals(listOf(14L), searched[0].amenities)
        assertEquals(searched[1], searched[0])
        assertEquals(searched[1].toString(), searched[0].toString(), "yachtSearchListCache key part")
        assertEquals(listOf(14L, 29L, 23L), searched[2].amenities)
        assertEquals(null, searched[3].amenities)
    }
}
