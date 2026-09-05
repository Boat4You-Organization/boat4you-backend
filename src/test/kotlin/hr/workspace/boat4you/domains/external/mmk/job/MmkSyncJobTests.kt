package hr.workspace.boat4you.domains.external.mmk.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.mmk.config.MmkRequestStats
import hr.workspace.boat4you.domains.external.mmk.service.MmkAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkOfferSyncRunSummary
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.annotation.Scheduled
import kotlin.test.Test
import kotlin.test.assertEquals

class MmkSyncJobTests {
    // Mockito matchers return null; route them through a generic helper (no mockito-kotlin on the classpath).
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val catalogue: MmkCatalogueIntegrationService = mock(MmkCatalogueIntegrationService::class.java)
    private val yachts: MmkYachtIntegrationService = mock(MmkYachtIntegrationService::class.java)
    private val offers: MmkYachtOfferIntegrationService = mock(MmkYachtOfferIntegrationService::class.java)
    private val availability: MmkAvailabilityIntegrationService = mock(MmkAvailabilityIntegrationService::class.java)
    private val cache: ServiceCallCacheService = mock(ServiceCallCacheService::class.java)
    private val stats = MmkRequestStats()

    private val job = MmkSyncJob(catalogue, yachts, offers, availability, cache, stats)

    @Test
    fun `near-term refresh runs the bounded sweep once and writes no offer marker`() {
        `when`(offers.nearTermOfferSync(any())).thenReturn(MmkOfferSyncRunSummary("near-term"))
        stats.tooManyRequests.incrementAndGet() // a 429 from before the run must not be attributed to it

        job.runNearTermOfferRefresh()

        verify(offers, times(1)).nearTermOfferSync(any())
        verify(offers, never()).yachtOfferSync()
        // SCHEDULED_MMK_YACHT_OFFER gates the 11:10 / 16:10 backup re-run of a failed 06:30 sweep —
        // a near-term pass must never mask that.
        verify(cache, never()).saveScheduledSync(any())
    }

    @Test
    fun `nightly offer sync keeps writing its finish marker`() {
        `when`(offers.yachtOfferSync()).thenReturn(MmkOfferSyncRunSummary("nightly"))

        job.runYachtOfferSync()

        verify(offers, times(1)).yachtOfferSync()
        verify(cache).saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_OFFER)
    }

    @Test
    fun `near-term job is scheduled at 10-50 and 16-50 UTC under its own PT2H lock`() {
        val method = MmkSyncJob::class.java.getMethod("runNearTermOfferRefresh")
        assertEquals("0 50 10,16 * * *", method.getAnnotation(Scheduled::class.java).cron)
        val lock = method.getAnnotation(SchedulerLock::class.java)
        assertEquals("mmkNearTermOfferRefresh", lock.name)
        assertEquals("PT2H", lock.lockAtMostFor)
    }
}
