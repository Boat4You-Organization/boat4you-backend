package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
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
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D1 (Mario, 5.9.2026): the search endpoint fires the live partner warm only for
 * non-weekly ranges; weekly ranges (7/14/21/28 d, any start day) are served from the
 * DB grid without touching NauSys or MMK. The per-yacht warms are not covered here.
 *
 * B3 (`NAUSYS_WARM_LONG_RANGES=ON`): the NauSys grid only stores Sat→Sat 7 nights, so every other future range in
 * 3..28 days reaches ExternalSyncService (which then decides per partner); Sat→Sat 7 nights stays DB-only.
 */
class YachtControllerWarmPolicyTests {
    // Mockito matchers return null; Kotlin inserts a null-check when a platform value meets a
    // non-null parameter, so route them through a generic helper (no mockito-kotlin on the classpath).
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val yachtQueryingService: YachtQueryingService = mock(YachtQueryingService::class.java)
    private val externalSyncService: ExternalSyncService = mock(ExternalSyncService::class.java)
    private var longRanges = RealPriceSwitches.OnOff.OFF
    private val switches: RealPriceSwitches =
        mock(RealPriceSwitches::class.java) { inv -> if (inv.method.name == "getNausysWarmLongRanges") longRanges else null }

    private val controller =
        YachtController(
            yachtQueryingService,
            mock(OfferQueryingService::class.java),
            externalSyncService,
            mock(UserRepository::class.java),
            mock(YachtTwinCanonicalService::class.java),
            mock(EquipmentAliases::class.java),
            switches,
        )

    private val saturday = LocalDate.of(2027, 6, 5)
    private val tuesday = LocalDate.of(2027, 6, 8)
    private val locations = listOf("c-115")

    @BeforeEach
    fun anonymousSearch() {
        SecurityContextHolder.clearContext() // anonymous → ANONYMOUS_USER_ID, isAdmin = false
        `when`(yachtQueryingService.getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
            .thenReturn(PageImpl(emptyList()))
    }

    /** One place for the ~35 positional params of getYachts, so a future param only breaks this helper. */
    private fun search(
        startDate: LocalDate?,
        endDate: LocalDate?,
        did: List<String>? = locations,
    ) = controller.getYachts(
        locations = did,
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
        startDate = startDate,
        endDate = endDate,
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
        countryCodes = null,
        page = 0,
        size = 10,
        lang = null,
    )

    private fun verifyNoWarm() {
        verify(externalSyncService, never()).syncYachtOffers(any<LocalDate>(), any<LocalDate>(), any<List<String>>())
    }

    @Test
    fun `weekly ranges are answered from the DB without any partner warm`() {
        listOf(
            saturday to saturday.plusWeeks(1),
            tuesday to tuesday.plusWeeks(2),
            saturday to saturday.plusWeeks(3),
            tuesday to tuesday.plusWeeks(4),
        ).forEach { (from, to) ->
            assertEquals(HttpStatus.OK, search(from, to).statusCode, "$from → $to")
        }
        verifyNoWarm()
        verify(yachtQueryingService, times(4)).getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())
    }

    @Test
    fun `non-weekly ranges still fire the live warm with the exact request`() {
        val to = saturday.plusDays(8)
        val response = search(saturday, to)
        assertEquals(HttpStatus.OK, response.statusCode)
        verify(externalSyncService, times(1)).syncYachtOffers(saturday, to, locations)

        val to10 = tuesday.plusDays(10)
        search(tuesday, to10)
        verify(externalSyncService, times(1)).syncYachtOffers(tuesday, to10, locations)
    }

    @Test
    fun `a dated search without locations never warms`() {
        assertEquals(HttpStatus.OK, search(saturday, saturday.plusDays(10), did = null).statusCode)
        verifyNoWarm()
    }

    @Test
    fun `an undated search never warms`() {
        assertEquals(HttpStatus.OK, search(null, null).statusCode)
        verifyNoWarm()
    }

    @Test
    fun `ranges outside 3-28 days are rejected before any warm`() {
        assertEquals(HttpStatus.BAD_REQUEST, search(saturday, saturday.plusDays(2)).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, search(saturday, saturday.plusDays(29)).statusCode)
        verifyNoWarm()
        verify(yachtQueryingService, never()).getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())
    }

    // B3 cases use dates relative to today, so they never drift into the past.
    private val futureSaturday = LocalDate.now().plusMonths(6).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    private val futureWednesday = futureSaturday.plusDays(4)

    @Test
    fun `switch ON - Sat to Sat 7 nights still never warms`() {
        longRanges = RealPriceSwitches.OnOff.ON
        assertEquals(HttpStatus.OK, search(futureSaturday, futureSaturday.plusWeeks(1)).statusCode)
        verifyNoWarm()
    }

    @Test
    fun `switch ON - 14, 21, 28 night Saturdays and a Wednesday week reach the per-partner warm`() {
        longRanges = RealPriceSwitches.OnOff.ON
        val ranges =
            listOf(
                futureSaturday to futureSaturday.plusWeeks(2),
                futureSaturday to futureSaturday.plusWeeks(3),
                futureSaturday to futureSaturday.plusWeeks(4),
                futureWednesday to futureWednesday.plusWeeks(1),
                futureSaturday to futureSaturday.plusDays(10),
            )
        ranges.forEach { (from, to) -> assertEquals(HttpStatus.OK, search(from, to).statusCode, "$from → $to") }
        ranges.forEach { (from, to) -> verify(externalSyncService, times(1)).syncYachtOffers(from, to, locations) }
    }

    @Test
    fun `switch ON - a past range never warms`() {
        longRanges = RealPriceSwitches.OnOff.ON
        val pastWednesday = LocalDate.now().minusDays(20).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
        search(pastWednesday, pastWednesday.plusWeeks(1))
        search(pastWednesday, pastWednesday.plusDays(10))
        verifyNoWarm()
    }

    @Test
    fun `switch OFF - the same weekly ranges stay DB-only (today)`() {
        longRanges = RealPriceSwitches.OnOff.OFF
        search(futureSaturday, futureSaturday.plusWeeks(2))
        search(futureWednesday, futureWednesday.plusWeeks(1))
        search(futureSaturday, futureSaturday.plusWeeks(1))
        verifyNoWarm()
        search(futureSaturday, futureSaturday.plusDays(10))
        verify(externalSyncService, times(1)).syncYachtOffers(futureSaturday, futureSaturday.plusDays(10), locations)
    }
}
