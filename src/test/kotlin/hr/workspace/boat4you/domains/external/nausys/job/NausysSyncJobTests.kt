package hr.workspace.boat4you.domains.external.nausys.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.nausys.config.NauSysRateLimitStats
import hr.workspace.boat4you.domains.external.nausys.service.NauSysAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
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
    private val offersAsync: NauSysYachtOfferIntegrationServiceAsync = mock(NauSysYachtOfferIntegrationServiceAsync::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyK(): T = ArgumentMatchers.any<T>() ?: null as T

    private val job =
        NausysSyncJob(offers, yachts, catalogue, availability, cache, NauSysRateLimitStats(), offersAsync).apply {
            availabilityWaitPollMs = 0
            availabilityWaitMaxMs = 0
            gateWaitMaxMs = 0
            sleep = {}
        }

    @BeforeEach
    fun emptySearchQueue() {
        // Mockito returns null for the Kotlin data class → NPE in the job without this stub.
        `when`(offersAsync.drainSearchRetryQueue(anyInt(), anyK())).thenReturn(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary())
    }

    private fun verifySearchDrains(count: Int) {
        verify(offersAsync, times(count)).drainSearchRetryQueue(anyInt(), anyK())
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
        verifySearchDrains(1)
        val order = inOrder(offers, offersAsync)
        order.verify(offers).drainRetryQueue()
        order.verify(offersAsync).drainSearchRetryQueue(anyInt(), anyK())
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
        verifySearchDrains(1)
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `a failing agency-queue drain still lets the search-queue drain run`() {
        doAnswer { throw IllegalStateException("boom") }.`when`(offers).drainRetryQueue()
        job.runYachtSync()
        verifySearchDrains(1)
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `nightly run waits for a gate held briefly by the search retry drain instead of skipping the night`() {
        job.nausysBusy.set(true)
        job.gateWaitMaxMs = 10_000
        job.sleep = { job.nausysBusy.set(false) } // the drain releases the gate during the first poll
        job.runYachtSync()
        verify(yachts, times(1)).yachtSync()
        verify(offers, times(1)).yachtOfferSync()
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
        verifySearchDrains(1)
        verify(offers, never()).yachtOfferSync()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `search retry drain skips when another NauSys sync holds the gate`() {
        job.nausysBusy.set(true)
        job.runSearchRetryDrain()
        verifySearchDrains(0)
        assertTrue(job.nausysBusy.get(), "skipping must not clear a gate it does not own")
    }

    @Test
    fun `search retry drain takes and releases the gate`() {
        job.runSearchRetryDrain()
        verifySearchDrains(1)
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `search retry drain failure does not leak the gate`() {
        doAnswer { throw IllegalStateException("db down") }.`when`(offersAsync).drainSearchRetryQueue(anyInt(), anyK())
        job.runSearchRetryDrain()
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
