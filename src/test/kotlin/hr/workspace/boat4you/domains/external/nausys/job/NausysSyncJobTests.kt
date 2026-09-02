package hr.workspace.boat4you.domains.external.nausys.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.nausys.config.NauSysRateLimitStats
import hr.workspace.boat4you.domains.external.nausys.service.NauSysAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NausysSyncJobTests {
    private val offers: NauSysYachtOfferIntegrationService = mock(NauSysYachtOfferIntegrationService::class.java)
    private val yachts: NauSysYachtIntegrationService = mock(NauSysYachtIntegrationService::class.java)
    private val catalogue: NauSysCatalogueIntegrationService = mock(NauSysCatalogueIntegrationService::class.java)
    private val availability: NauSysAvailabilityIntegrationService = mock(NauSysAvailabilityIntegrationService::class.java)
    private val cache: ServiceCallCacheService = mock(ServiceCallCacheService::class.java)

    private val job =
        NausysSyncJob(offers, yachts, catalogue, availability, cache, NauSysRateLimitStats()).apply {
            availabilityWaitPollMs = 0
            availabilityWaitMaxMs = 0
            sleep = {}
        }

    @Test
    fun `nightly run chains availability and retry drain, and a concurrent second run is skipped`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            null
        }.`when`(yachts).yachtSync()

        val first = Thread { job.runYachtSync() }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        job.runYachtSync() // second caller while the first is inside yachtSync
        verify(yachts, times(1)).yachtSync()

        release.countDown()
        first.join(5_000)

        verify(offers, times(1)).yachtOfferSync()
        verify(availability, times(1)).syncYachtAvailability()
        verify(offers, times(1)).drainRetryQueue()
        verify(cache).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)
        verify(cache).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `availability pass skips when another NauSys sync holds the gate past the wait budget`() {
        job.nausysBusy.set(true)
        job.availabilitySync()
        verify(availability, never()).syncYachtAvailability()
        assertTrue(job.nausysBusy.get(), "skipping must not clear a gate it does not own")
    }

    @Test
    fun `availability pass waits for the gate and then runs`() {
        job.nausysBusy.set(true)
        job.availabilityWaitMaxMs = 10_000
        job.sleep = { job.nausysBusy.set(false) } // the running sync finishes during the first poll
        job.availabilitySync()
        verify(availability, times(1)).syncYachtAvailability()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `a chained availability failure does not break the run or leak the gate`() {
        doAnswer { throw IllegalStateException("boom") }.`when`(availability).syncYachtAvailability()
        job.runYachtSync()
        verify(offers, times(1)).drainRetryQueue()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `backup slot always drains the queue but never starts a second offer sync while a night is in flight`() {
        `when`(cache.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)).thenReturn(false)
        `when`(cache.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)).thenReturn(true)
        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)).thenReturn(Instant.now().minus(1, ChronoUnit.HOURS))
        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)).thenReturn(Instant.now().minus(30, ChronoUnit.HOURS))

        job.runYachtBackupSync()

        verify(offers, times(1)).drainRetryQueue()
        verify(offers, never()).yachtOfferSync()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `nightlyOfferSyncStillRunning is false once finished, when never started, or when the start is stale`() {
        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)).thenReturn(null)
        assertFalse(job.nightlyOfferSyncStillRunning())

        val started = Instant.now().minus(2, ChronoUnit.HOURS)
        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)).thenReturn(started)
        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)).thenReturn(started.plusSeconds(3600))
        assertFalse(job.nightlyOfferSyncStillRunning())

        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)).thenReturn(null)
        assertTrue(job.nightlyOfferSyncStillRunning())

        `when`(cache.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)).thenReturn(Instant.now().minus(9, ChronoUnit.HOURS))
        assertFalse(job.nightlyOfferSyncStillRunning(), "a crashed night older than 8 h must not block the backup forever")
    }
}
