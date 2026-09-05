package hr.workspace.boat4you.domains.external.service

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
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D2 (Mario, 5.9.2026): the location-path warm never retries NauSys from the API node.
 * A transient NauSys failure is parked in nausys_search_sync_retry, the MMK part still
 * runs and the 3 h marker is still written; non-transient failures are logged only.
 * Plain Mockito, no Spring: @Async is a no-op without a proxy, so the call is synchronous.
 */
class ExternalSyncServiceSearchWarmTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val nausysAsync: NauSysYachtOfferIntegrationServiceAsync = mock(NauSysYachtOfferIntegrationServiceAsync::class.java)
    private val mmkAsync: MmkYachtOfferIntegrationServiceAsync = mock(MmkYachtOfferIntegrationServiceAsync::class.java)
    private val locationViewRepository: LocationViewRepository = mock(LocationViewRepository::class.java)
    private val cache: ServiceCallCacheService = mock(ServiceCallCacheService::class.java)
    private val queue: NausysSearchSyncRetryQueue = mock(NausysSearchSyncRetryQueue::class.java)

    private val service =
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
}
