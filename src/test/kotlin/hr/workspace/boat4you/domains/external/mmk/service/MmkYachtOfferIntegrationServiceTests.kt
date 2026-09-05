package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync.Companion.fullHorizonWindows
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MmkYachtOfferIntegrationServiceTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private val agencyRepository: AgencyRepository = mock(AgencyRepository::class.java)
    private val async: MmkYachtOfferIntegrationServiceAsync = mock(MmkYachtOfferIntegrationServiceAsync::class.java)

    private val service =
        MmkYachtOfferIntegrationService(
            agencyRepository,
            SyncConfigurationProperties(offerMaxYears = 1, minDurationDays = 3),
            mock(MmkYachtOfferSyncService::class.java),
            async,
            mock(MmkOfferIntegrationUtils::class.java),
            mock(MmkRetryableClient::class.java),
        )

    private val today = LocalDate.of(2026, 9, 5)
    private val recordedWindows = mutableListOf<List<ClosedRange<LocalDate>>>()
    private val recordedClip = mutableListOf<Boolean>()

    private fun agency(
        id: Long,
        externalId: Long,
    ) = Agency().apply {
        this.id = id
        // primarySource is a derived getter over agencySources (primary == true).
        agencySources =
            mutableSetOf(
                AgencySource().apply {
                    primary = true
                    this.externalId = externalId
                },
            )
    }

    @BeforeTest
    fun stubAgencies() {
        `when`(agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(1L)).thenReturn(setOf(agency(7, 555), agency(8, 556)))
        `when`(async.syncOffersForAgencyYachts(any(), anyLong(), any(), anyBoolean())).thenAnswer { invocation ->
            val agency: Agency = invocation.getArgument(0)
            recordedWindows.add(invocation.getArgument(2))
            recordedClip.add(invocation.getArgument(3))
            CompletableFuture.completedFuture(
                MmkAgencyOfferSyncResult(
                    calls = 2,
                    failures = if (agency.id == 8L) 1 else 0,
                    upsert = MmkOfferUpsertCounters().apply { upserted = 10 },
                ),
            )
        }
    }

    @Test
    fun `near-term sync passes the clipped 12-week window to every agency and aggregates their results`() {
        val summary = service.nearTermOfferSync(today)

        assertEquals(2, recordedWindows.size)
        assertTrue(recordedWindows.all { it == listOf(today..today.plusDays(84)) }, recordedWindows.toString())
        assertTrue(recordedClip.all { it })
        assertEquals(2, summary.agencies)
        assertEquals(4, summary.calls)
        assertEquals(1, summary.failures)
        assertEquals(20, summary.upsert.upserted)
        assertEquals(0, summary.timedOutBatches)
        assertTrue(summary.toLogLine().startsWith("MMK offer sync (near-term): agencies=2 calls=4 failures=1"), summary.toLogLine())
    }

    @Test
    fun `nightly sync passes the month-anchored full horizon unclipped`() {
        val summary = service.yachtOfferSync()

        val expected = fullHorizonWindows(LocalDate.now(), 1)
        assertEquals(2, recordedWindows.size)
        assertTrue(recordedWindows.all { it == expected }, recordedWindows.toString())
        assertTrue(recordedClip.none { it })
        assertEquals(4, summary.calls)
    }
}
