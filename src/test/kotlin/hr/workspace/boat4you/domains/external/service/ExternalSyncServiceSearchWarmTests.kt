package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalLocationDto
import hr.workspace.boat4you.domains.catalouge.jpa.LocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.nausys.service.NausysSearchSyncRetryQueue
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D2 (Mario, 5.9.2026): the location-path warm never retries NauSys from the API node.
 * A transient NauSys failure is parked in nausys_search_sync_retry, the MMK part still
 * runs and the 3 h marker is still written; non-transient failures are logged only.
 * Plain Mockito, no Spring: @Async is a no-op without a proxy, so the call is synchronous.
 *
 * The tests above the B3 marker run with `NAUSYS_WARM_LONG_RANGES=OFF` and are the regression for today's
 * behaviour (one combined 3 h marker, no budget). The B3 tests below run with the switch ON: per-partner rule and
 * per-partner markers, the live NauSys call behind [LiveSearchWarmBudget] (daily cap, rate-limit circuit).
 */
class ExternalSyncServiceSearchWarmTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val nausysAsync: NauSysYachtOfferIntegrationServiceAsync = mock(NauSysYachtOfferIntegrationServiceAsync::class.java)
    private val mmkAsync: MmkYachtOfferIntegrationServiceAsync = mock(MmkYachtOfferIntegrationServiceAsync::class.java)
    private val locationViewRepository: LocationViewRepository = mock(LocationViewRepository::class.java)
    private val cache: ServiceCallCacheService = mock(ServiceCallCacheService::class.java)
    private val queue: NausysSearchSyncRetryQueue = mock(NausysSearchSyncRetryQueue::class.java)
    private val markers: PartnerSearchWarmMarkers = mock(PartnerSearchWarmMarkers::class.java)

    private var longRanges = RealPriceSwitches.OnOff.OFF
    private val switches: RealPriceSwitches =
        mock(RealPriceSwitches::class.java) { inv -> if (inv.method.name == "getNausysWarmLongRanges") longRanges else null }

    /** A mutable UTC clock for the budget. */
    private class TestClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = TestClock(Instant.parse("2026-10-14T10:00:00Z"))
    private val budget = newBudget(cap = 1200)

    private fun newBudget(cap: Int) = LiveSearchWarmBudget(cap).also { it.clock = clock }

    private val service = serviceWith(budget)

    private fun serviceWith(budget: LiveSearchWarmBudget) =
        ExternalSyncService(
            nausysAsync,
            mmkAsync,
            locationViewRepository,
            mock(YachtRepository::class.java),
            mock(NauSysYachtOfferIntegrationService::class.java),
            mock(MmkYachtOfferIntegrationService::class.java),
            mock(ExternalMappingService::class.java),
            cache,
            mock(YachtSyncMutex::class.java),
            queue,
            mock(PlatformTransactionManager::class.java),
            switches,
            budget,
            markers,
        )

    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusDays(10)
    private val locations = listOf("c-115")
    private val nausysCountryId = 1L

    @BeforeEach
    fun croatiaMapsToBothPartners() {
        `when`(cache.shouldCallYachtSearch(from, to, locations)).thenReturn(true)
        `when`(locationViewRepository.findExternalIdById("c-115", LocationType.COUNTRY.value)).thenReturn(
            listOf(
                ExternalLocationDto(115, "HR", nausysCountryId, ExternalSystemEnum.NAUSYS.value, LocationType.COUNTRY),
                ExternalLocationDto(115, "HR", 9L, ExternalSystemEnum.MMK.value, LocationType.COUNTRY),
            ),
        )
    }

    private fun nausysFailsWith(e: Throwable) {
        doThrow(e).`when`(nausysAsync).syncOffersForDateRangeBlocking(from, to, listOf(nausysCountryId), null, null)
    }

    private fun verifyMmkAndMarker() {
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(from, to, listOf("HR"), null, null)
        verify(cache, times(1)).saveYachtSearch(from, to, locations)
    }

    private fun verifyEnqueued(e: Throwable) {
        verify(queue, times(1)).enqueue(from, to, listOf(nausysCountryId), null, null, e)
    }

    private fun verifyNothingEnqueued() {
        verify(queue, never()).enqueue(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `successful warm calls both partners with the resolved ids and saves the marker`() {
        service.syncYachtOffers(from, to, locations)

        verify(nausysAsync, times(1)).syncOffersForDateRangeBlocking(from, to, listOf(nausysCountryId), null, null)
        verifyMmkAndMarker()
        verifyNothingEnqueued()
    }

    @Test
    fun `a NauSys 429 budget failure is parked for the scheduler, MMK still runs and the marker is saved`() {
        val e = NauSysRateLimitedException("/freeYachtsSearch", 6)
        nausysFailsWith(e)

        service.syncYachtOffers(from, to, locations)

        verifyEnqueued(e)
        verifyMmkAndMarker()
    }

    @Test
    fun `a NauSys 502 is parked as well`() {
        val e = HttpServerErrorException(HttpStatus.BAD_GATEWAY)
        nausysFailsWith(e)

        service.syncYachtOffers(from, to, locations)

        verifyEnqueued(e)
        verifyMmkAndMarker()
    }

    @Test
    fun `a NauSys timeout and a generic partner exception are parked as well`() {
        val timeout = ResourceAccessException("read timed out")
        nausysFailsWith(timeout)
        service.syncYachtOffers(from, to, locations)
        verifyEnqueued(timeout)

        val partner = ExternalSystemException("NauSys said no")
        nausysFailsWith(partner)
        service.syncYachtOffers(from, to, locations)
        verifyEnqueued(partner)

        verify(mmkAsync, times(2)).syncOffersForDateRangeBlocking(from, to, listOf("HR"), null, null)
        verify(cache, times(2)).saveYachtSearch(from, to, locations)
    }

    @Test
    fun `a non-transient NauSys failure is logged, not queued, and does not stop MMK or the marker`() {
        nausysFailsWith(IllegalStateException("mapping bug"))
        service.syncYachtOffers(from, to, locations)

        nausysFailsWith(HttpClientErrorException(HttpStatus.BAD_REQUEST))
        service.syncYachtOffers(from, to, locations)

        verifyNothingEnqueued()
        verify(mmkAsync, times(2)).syncOffersForDateRangeBlocking(from, to, listOf("HR"), null, null)
        verify(cache, times(2)).saveYachtSearch(from, to, locations)
    }

    @Test
    fun `a fresh 3 h marker short-circuits everything`() {
        `when`(cache.shouldCallYachtSearch(from, to, locations)).thenReturn(false)

        service.syncYachtOffers(from, to, locations)

        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(mmkAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(cache, never()).saveYachtSearch(any(), any(), any())
        verifyNothingEnqueued()
    }

    @Test
    fun `a failing enqueue is swallowed, MMK and the marker still run`() {
        nausysFailsWith(NauSysRateLimitedException("/freeYachtsSearch", 6))
        doAnswer { throw IllegalStateException("db down") }.`when`(queue).enqueue(any(), any(), any(), any(), any(), any())

        service.syncYachtOffers(from, to, locations)

        verifyMmkAndMarker()
    }

    @Test
    fun `locations without any NauSys mapping skip the NauSys call instead of firing a world-wide search`() {
        `when`(locationViewRepository.findExternalIdById("c-115", LocationType.COUNTRY.value)).thenReturn(
            listOf(ExternalLocationDto(115, "HR", 9L, ExternalSystemEnum.MMK.value, LocationType.COUNTRY)),
        )

        service.syncYachtOffers(from, to, locations)

        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verifyNothingEnqueued()
        verifyMmkAndMarker()
    }

    @Test
    fun `an identical warm that is still in flight is not started twice`() {
        // A paginated sister-site search fires the same (dates, locations) warm once per
        // page within a second; only the first may reach the partners.
        val nausysEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            nausysEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            null
        }.`when`(nausysAsync).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())

        val first = thread { service.syncYachtOffers(from, to, locations) }
        assertTrue(nausysEntered.await(5, TimeUnit.SECONDS), "first warm did not reach NauSys")

        service.syncYachtOffers(from, to, locations) // duplicate while the first is in flight
        release.countDown()
        first.join(5_000)

        verify(nausysAsync, times(1)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(cache, times(1)).saveYachtSearch(from, to, locations)

        // Once finished, the same request may warm again (subject to the 3 h marker).
        service.syncYachtOffers(from, to, locations)
        verify(nausysAsync, times(2)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
    }

    // ---------------------------------------------------------------------------------------------------------
    // B3: NAUSYS_WARM_LONG_RANGES=ON. Dates relative to today: the policy compares with LocalDate.now().
    // ---------------------------------------------------------------------------------------------------------

    private val saturday = LocalDate.now().plusMonths(6).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    private val wednesday = saturday.plusDays(4)

    /** Seven distinct non-weekly ranges from [saturday] (both partners due). */
    private fun nonWeeklyEnds(): List<LocalDate> = listOf(8L, 9L, 10L, 11L, 12L, 13L, 15L).map { saturday.plusDays(it) }

    private fun switchOn() {
        longRanges = RealPriceSwitches.OnOff.ON
    }

    /** Both per-partner markers stale for [start]→[end]. */
    private fun markersStale(
        start: LocalDate,
        end: LocalDate,
    ) {
        `when`(markers.isDue(ExternalSystemEnum.NAUSYS, start, end, locations)).thenReturn(true)
        `when`(markers.isDue(ExternalSystemEnum.MMK, start, end, locations)).thenReturn(true)
    }

    private fun verifyLegacyMarkerUntouched() {
        verify(cache, never()).shouldCallYachtSearch(any(), any(), any())
        verify(cache, never()).saveYachtSearch(any(), any(), any())
    }

    private fun verifyNauSysCalled(
        start: LocalDate,
        end: LocalDate,
        times: Int = 1,
    ) = verify(nausysAsync, times(times)).syncOffersForDateRangeBlocking(start, end, listOf(nausysCountryId), null, null)

    @Test
    fun `switch ON - a 14-night Saturday warms NauSys only and writes only the NauSys marker`() {
        switchOn()
        val end = saturday.plusWeeks(2)
        markersStale(saturday, end)

        service.syncYachtOffers(saturday, end, locations)

        verifyNauSysCalled(saturday, end)
        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
        verify(mmkAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(markers, never()).save(ExternalSystemEnum.MMK, saturday, end, locations)
        verifyLegacyMarkerUntouched()
        verifyNothingEnqueued()
        assertEquals(1, budget.callsToday())
    }

    @Test
    fun `switch ON - a Wednesday week warms NauSys, a Saturday week warms nobody`() {
        switchOn()
        markersStale(wednesday, wednesday.plusWeeks(1))
        markersStale(saturday, saturday.plusWeeks(1))

        service.syncYachtOffers(wednesday, wednesday.plusWeeks(1), locations)
        service.syncYachtOffers(saturday, saturday.plusWeeks(1), locations)

        verifyNauSysCalled(wednesday, wednesday.plusWeeks(1))
        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(saturday, saturday.plusWeeks(1), listOf(nausysCountryId), null, null)
        verify(mmkAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(markers, never()).save(ExternalSystemEnum.NAUSYS, saturday, saturday.plusWeeks(1), locations)
        verifyLegacyMarkerUntouched()
    }

    @Test
    fun `switch ON - a non-weekly range warms both partners, each with its own marker`() {
        switchOn()
        val end = saturday.plusDays(10)
        markersStale(saturday, end)

        service.syncYachtOffers(saturday, end, locations)

        verifyNauSysCalled(saturday, end)
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(saturday, end, listOf("HR"), null, null)
        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
        verify(markers, times(1)).save(ExternalSystemEnum.MMK, saturday, end, locations)
        verifyLegacyMarkerUntouched()
    }

    @Test
    fun `switch ON - a fresh partner marker skips only that partner`() {
        switchOn()
        val end = saturday.plusDays(10)
        `when`(markers.isDue(ExternalSystemEnum.NAUSYS, saturday, end, locations)).thenReturn(false)
        `when`(markers.isDue(ExternalSystemEnum.MMK, saturday, end, locations)).thenReturn(true)

        service.syncYachtOffers(saturday, end, locations)

        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(saturday, end, listOf("HR"), null, null)
        verify(markers, never()).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
        verify(markers, times(1)).save(ExternalSystemEnum.MMK, saturday, end, locations)
        assertEquals(0, budget.callsToday())

        `when`(markers.isDue(ExternalSystemEnum.NAUSYS, saturday, end, locations)).thenReturn(true)
        `when`(markers.isDue(ExternalSystemEnum.MMK, saturday, end, locations)).thenReturn(false)
        service.syncYachtOffers(saturday, end, locations)

        verifyNauSysCalled(saturday, end)
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
    }

    @Test
    fun `switch ON - both markers fresh short-circuits everything`() {
        switchOn()
        service.syncYachtOffers(saturday, saturday.plusDays(10), locations) // mocks answer false = fresh

        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(mmkAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verify(locationViewRepository, never()).findExternalIdById(any(), any())
        verifyNothingEnqueued()
    }

    @Test
    fun `switch ON - an MMK failure keeps the NauSys marker and writes no MMK marker`() {
        switchOn()
        val end = saturday.plusDays(10)
        markersStale(saturday, end)
        doThrow(IllegalStateException("mmk down")).`when`(mmkAsync).syncOffersForDateRangeBlocking(saturday, end, listOf("HR"), null, null)

        service.syncYachtOffers(saturday, end, locations)

        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
        verify(markers, never()).save(ExternalSystemEnum.MMK, saturday, end, locations)
    }

    @Test
    fun `switch ON - over the daily cap the warm is parked, not called, and still marked`() {
        switchOn()
        val capped = serviceWith(newBudget(cap = 1))
        val end1 = saturday.plusWeeks(2)
        val end2 = saturday.plusWeeks(3)
        markersStale(saturday, end1)
        markersStale(saturday, end2)

        capped.syncYachtOffers(saturday, end1, locations) // uses the one call of the day
        capped.syncYachtOffers(saturday, end2, locations) // over the cap

        verifyNauSysCalled(saturday, end1)
        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(saturday, end2, listOf(nausysCountryId), null, null)
        val deferred = captureEnqueued(saturday, end2)
        assertEquals("live search warm deferred to the scheduler: DAILY_CAP", deferred.message)
        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end2, locations)

        // the next UTC day has a fresh cap
        clock.now = clock.now.plus(Duration.ofDays(1))
        val end3 = saturday.plusWeeks(4)
        markersStale(saturday, end3)
        capped.syncYachtOffers(saturday, end3, locations)
        verifyNauSysCalled(saturday, end3)
    }

    @Test
    fun `switch ON - a NauSys-less location consumes no budget`() {
        switchOn()
        val capped = newBudget(cap = 1)
        `when`(locationViewRepository.findExternalIdById("c-115", LocationType.COUNTRY.value)).thenReturn(
            listOf(ExternalLocationDto(115, "HR", 9L, ExternalSystemEnum.MMK.value, LocationType.COUNTRY)),
        )
        markersStale(saturday, saturday.plusDays(10))

        serviceWith(capped).syncYachtOffers(saturday, saturday.plusDays(10), locations)

        verify(nausysAsync, never()).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        verifyNothingEnqueued()
        assertEquals(0, capped.callsToday())
    }

    @Test
    fun `switch ON - five rate limits in ten minutes open the circuit for fifteen minutes`() {
        switchOn()
        val rateLimited = NauSysRateLimitedException("/freeYachtsSearch", 6)
        doThrow(rateLimited).`when`(nausysAsync).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        val ends = nonWeeklyEnds()
        ends.forEach { markersStale(saturday, it) }

        ends.take(5).forEach { end ->
            service.syncYachtOffers(saturday, end, locations)
            clock.now = clock.now.plus(Duration.ofMinutes(2)) // 5 failures within 8 minutes
        }
        verify(nausysAsync, times(5)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        ends.take(5).forEach { verify(queue, times(1)).enqueue(saturday, it, listOf(nausysCountryId), null, null, rateLimited) }

        // circuit open: parked without a call
        service.syncYachtOffers(saturday, ends[5], locations)
        verify(nausysAsync, times(5)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        assertEquals("live search warm deferred to the scheduler: CIRCUIT_OPEN", captureEnqueued(saturday, ends[5]).message)
        // MMK still runs while the circuit is open
        verify(mmkAsync, times(1)).syncOffersForDateRangeBlocking(saturday, ends[5], listOf("HR"), null, null)

        // 15 minutes after opening, live calls resume
        clock.now = clock.now.plus(Duration.ofMinutes(16))
        service.syncYachtOffers(saturday, ends[6], locations)
        verify(nausysAsync, times(6)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
    }

    @Test
    fun `switch ON - rate limits spread over more than ten minutes do not open the circuit`() {
        switchOn()
        doThrow(NauSysRateLimitedException("/freeYachtsSearch", 6))
            .`when`(nausysAsync)
            .syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        val ends = nonWeeklyEnds().take(6)
        ends.forEach { markersStale(saturday, it) }

        ends.forEach { end ->
            service.syncYachtOffers(saturday, end, locations)
            clock.now = clock.now.plus(Duration.ofMinutes(3)) // never 5 inside one 10-minute window
        }

        verify(nausysAsync, times(6)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
    }

    @Test
    fun `switch ON - a 5xx is parked as today and does not count towards the circuit`() {
        switchOn()
        val e = HttpServerErrorException(HttpStatus.BAD_GATEWAY)
        doThrow(e).`when`(nausysAsync).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        val ends = nonWeeklyEnds()
        ends.forEach { markersStale(saturday, it) }

        ends.forEach { service.syncYachtOffers(saturday, it, locations) }

        verify(nausysAsync, times(ends.size)).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())
        ends.forEach { verify(queue, times(1)).enqueue(saturday, it, listOf(nausysCountryId), null, null, e) }
    }

    @Test
    fun `switch ON - an identical warm that is still in flight is not started twice`() {
        switchOn()
        val end = saturday.plusWeeks(2)
        markersStale(saturday, end)
        val nausysEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            nausysEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            null
        }.`when`(nausysAsync).syncOffersForDateRangeBlocking(any(), any(), any(), any(), any())

        val first = thread { service.syncYachtOffers(saturday, end, locations) }
        assertTrue(nausysEntered.await(5, TimeUnit.SECONDS), "first warm did not reach NauSys")
        service.syncYachtOffers(saturday, end, locations)
        release.countDown()
        first.join(5_000)

        verifyNauSysCalled(saturday, end)
        verify(markers, times(1)).save(ExternalSystemEnum.NAUSYS, saturday, end, locations)
        assertEquals(1, budget.callsToday())
    }

    @Test
    fun `switch OFF - the budget is never consulted (no cap, no circuit, today)`() {
        val zeroCap = newBudget(cap = 0)
        val offService = serviceWith(zeroCap)

        offService.syncYachtOffers(from, to, locations)

        verify(nausysAsync, times(1)).syncOffersForDateRangeBlocking(from, to, listOf(nausysCountryId), null, null)
        verifyMmkAndMarker()
        verifyNothingEnqueued()
        verifyNoInteractions(markers)
    }

    private fun captureEnqueued(
        start: LocalDate,
        end: LocalDate,
    ): Throwable {
        val captor = ArgumentCaptor.forClass(Throwable::class.java)
        // Matchers return null; hand Kotlin's non-null parameters the value itself (the matcher is already registered).
        verify(queue, times(1)).enqueue(
            ArgumentMatchers.eq(start) ?: start,
            ArgumentMatchers.eq(end) ?: end,
            ArgumentMatchers.eq(listOf(nausysCountryId)),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.isNull(),
            captor.capture() ?: RuntimeException(),
        )
        return assertIs<LiveSearchWarmBudget.DeferredException>(captor.value)
    }
}
