package hr.workspace.boat4you.domains.external.nausys.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.nausys.config.NauSysRateLimitStats
import hr.workspace.boat4you.domains.external.nausys.service.NauSysAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysOfferSyncRunSummary
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NausysSyncJobTests {
    // Mockito matchers return null; route them through a generic helper (no mockito-kotlin on the classpath).
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val offers: NauSysYachtOfferIntegrationService = mock(NauSysYachtOfferIntegrationService::class.java)
    private val yachts: NauSysYachtIntegrationService = mock(NauSysYachtIntegrationService::class.java)
    private val catalogue: NauSysCatalogueIntegrationService = mock(NauSysCatalogueIntegrationService::class.java)
    private val availability: NauSysAvailabilityIntegrationService = mock(NauSysAvailabilityIntegrationService::class.java)
    private val cache: ServiceCallCacheService = mock(ServiceCallCacheService::class.java)

    private val job =
        NausysSyncJob(offers, yachts, catalogue, availability, cache, NauSysRateLimitStats()).apply {
            availabilityWaitPollMs = 0
            availabilityWaitMaxMs = 0
            nearTermWaitMaxMs = 0
            sleep = {}
            today = { LocalDate.of(2026, 9, 5) }
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
    fun `near-term refresh syncs the 12-week window, marks it, drains the queue and releases the gate`() {
        `when`(offers.yachtOfferSync(any())).thenReturn(NauSysOfferSyncRunSummary())

        job.runNearTermOfferRefresh()

        verify(offers, times(1)).yachtOfferSync(LocalDate.of(2026, 11, 28)) // 2026-09-05 + 84 d
        verify(cache).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_NEAR_TERM_OFFER)
        // The nightly's markers gate the backup slot's full re-run and must stay untouched.
        verify(cache, never()).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        verify(cache, never()).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)
        verify(offers, times(1)).drainRetryQueue()
        verify(availability, never()).syncYachtAvailability()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `near-term refresh skips when another NauSys sync holds the gate past the wait budget`() {
        job.nausysBusy.set(true)
        job.runNearTermOfferRefresh()
        verify(offers, never()).yachtOfferSync(any())
        verify(offers, never()).drainRetryQueue()
        assertTrue(job.nausysBusy.get(), "skipping must not clear a gate it does not own")
    }

    @Test
    fun `near-term refresh waits for the gate and then runs`() {
        `when`(offers.yachtOfferSync(any())).thenReturn(NauSysOfferSyncRunSummary())
        job.nausysBusy.set(true)
        job.nearTermWaitMaxMs = 10_000
        job.sleep = { job.nausysBusy.set(false) } // the running sync finishes during the first poll
        job.runNearTermOfferRefresh()
        verify(offers, times(1)).yachtOfferSync(any())
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `a failing near-term refresh releases the gate and writes no marker`() {
        `when`(offers.yachtOfferSync(any())).thenThrow(IllegalStateException("boom"))
        assertFailsWith<IllegalStateException> { job.runNearTermOfferRefresh() }
        verify(cache, never()).saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_NEAR_TERM_OFFER)
        verify(offers, never()).drainRetryQueue()
        assertFalse(job.nausysBusy.get())
    }

    @Test
    fun `nightly lock covers the measured 6h39m run and the near-term job has its own cron and lock`() {
        val nightly = NausysSyncJob::class.java.getMethod("runYachtSync").getAnnotation(SchedulerLock::class.java)
        assertEquals("PT10H", nightly.lockAtMostFor)

        val nearTerm = NausysSyncJob::class.java.getMethod("runNearTermOfferRefresh")
        assertEquals("0 40 10,16 * * *", nearTerm.getAnnotation(Scheduled::class.java).cron)
        val lock = nearTerm.getAnnotation(SchedulerLock::class.java)
        assertEquals("nausysNearTermOfferRefresh", lock.name)
        assertEquals("PT3H", lock.lockAtMostFor)
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
