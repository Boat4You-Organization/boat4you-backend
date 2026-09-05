package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ReservationOption
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import hr.workspace.boat4you.domains.external.model.SyncInterval
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.NausysOfferSyncRetry
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NauSysYachtOfferIntegrationServiceTests {
    // Mockito matchers return null; Kotlin inserts a null-check when a platform value meets a
    // non-null parameter, so route them through generic helpers (no mockito-kotlin on the classpath).
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> ArgumentCaptor<T>.captureK(): T = capture() ?: null as T

    private val agencyRepository: AgencyRepository = mock(AgencyRepository::class.java)
    private val yachtRepository: YachtRepository = mock(YachtRepository::class.java)
    private val externalMappingService: ExternalMappingService = mock(ExternalMappingService::class.java)
    private val externalSystemService: ExternalSystemService = mock(ExternalSystemService::class.java)
    private val syncService: NauSysYachtOfferSyncService = mock(NauSysYachtOfferSyncService::class.java)
    private val client: NauSysRetryableClient = mock(NauSysRetryableClient::class.java)
    private val queue: NausysOfferSyncRetryQueue = mock(NausysOfferSyncRetryQueue::class.java)

    private val service =
        NauSysYachtOfferIntegrationService(
            agencyRepository,
            yachtRepository,
            externalMappingService,
            NauSysAuthProvider("user", "pass"),
            externalSystemService,
            syncService,
            mock(SyncConfigurationProperties::class.java),
            client,
            queue,
        )

    private val agency = Agency().apply { id = 7 }
    private val yachtIds = listOf(101L, 102L)
    private val d0 = LocalDate.of(2027, 5, 1)
    private val intervals = (0 until 3).map { SyncInterval(d0.plusWeeks(it.toLong()), d0.plusWeeks(it.toLong() + 1)) }
    private val today: LocalDate = LocalDate.now()

    private fun saturdayGroup(end: LocalDate) = ReservationOptionsGroup(start = today, end = end, minimalDuration = 7, checkinSat = true, checkoutSat = true)

    /** One NauSys agency (id 7) with one Saturday-to-Saturday yacht (id 55 ↔ NauSys 101) whose season ends at [seasonEnd]. */
    private fun stubOneAgencyWithOneSaturdayYacht(seasonEnd: LocalDate) {
        val nausys = ExternalSystem().apply { id = 2 }
        `when`(externalSystemService.findById(2L)).thenReturn(nausys)
        `when`(agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(2L)).thenReturn(setOf(agency))
        val yacht =
            Yacht().apply {
                id = 55
                reservationOptions =
                    mutableSetOf(
                        ReservationOption().apply {
                            dateFrom = today.minusDays(30)
                            dateTo = seasonEnd
                            minimalDuration = 7
                            checkinSat = true
                            checkoutSat = true
                        },
                    )
            }
        `when`(yachtRepository.findWithReservationOptionsByAgency(agency)).thenReturn(listOf(yacht))
        `when`(externalMappingService.getAllMappingsByTypeAndExtendedType("Yacht", nausys, YACHT_AGENCY_EXTERNAL_MAPPING_KEY + 7))
            .thenReturn(listOf(ExternalMapping(101L, 55L, "Yacht", nausys, YACHT_AGENCY_EXTERNAL_MAPPING_KEY + 7)))
    }

    @Test
    fun `calcSyncEndDate keeps the 18-month cap by default and honours an explicit horizon`() {
        val longSeason = saturdayGroup(end = today.plusYears(3))
        val shortSeason = saturdayGroup(end = today.plusMonths(2))
        assertEquals(today.plusMonths(18), service.calcSyncEndDate(longSeason))
        assertEquals(shortSeason.end, service.calcSyncEndDate(shortSeason))

        val horizon = today.plusDays(84)
        assertEquals(horizon, service.calcSyncEndDate(longSeason, horizon))
        assertEquals(shortSeason.end, service.calcSyncEndDate(shortSeason, horizon), "the season end stays the upper bound")
    }

    @Test
    fun `near-term horizon bounds every generated interval while the default path still reaches the season end`() {
        stubOneAgencyWithOneSaturdayYacht(seasonEnd = today.plusDays(400))
        val requests = mutableListOf<RestFreeYachtsRequest>()
        `when`(client.getFreeYachts(any())).thenAnswer {
            requests.add(it.getArgument(0))
            RestFreeYachtList(status = "OK", freeYachts = emptyList())
        }
        val horizon = today.plusDays(84)

        val summary = service.yachtOfferSync(horizon)

        assertEquals(0, summary.agencyFailures, "fixture must sync cleanly")
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.periodTo.value!! <= horizon }, "every interval must end within the horizon: ${requests.map { it.periodTo.value }}")
        assertTrue(requests.all { it.periodFrom.value!! >= today })
        val saturdayWeeksInsideHorizon =
            generateSequence(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))) { it.plusWeeks(1) }
                .takeWhile { it.plusDays(7) <= horizon }
                .count()
        assertEquals(saturdayWeeksInsideHorizon, requests.size, "the whole window is covered, one call per Saturday week")
        assertEquals(1, summary.agencies)
        assertEquals(saturdayWeeksInsideHorizon, summary.intervals)
        assertEquals(0, summary.deferred)

        requests.clear()
        service.yachtOfferSync()

        val furthest = requests.maxOf { it.periodTo.value!! }
        assertTrue(furthest > horizon, "the default (nightly) path must not be capped at 12 weeks: $furthest")
        assertTrue(furthest <= today.plusDays(400), "the default path stays bounded by the season end: $furthest")
    }

    @Test
    fun `run summary counts intervals, returned offers and deferred weeks`() {
        val calls = AtomicInteger()
        `when`(client.getFreeYachts(any())).thenAnswer {
            if (calls.incrementAndGet() == 2) throw NauSysRateLimitedException("/freeYachts", 6)
            RestFreeYachtList(status = "OK", freeYachts = listOf(RestFreeYacht(yachtId = 101), RestFreeYacht(yachtId = 102)))
        }
        val summary = NauSysOfferSyncRunSummary()

        service.syncIntervals(agency, emptyList(), yachtIds, intervals, skipDisappearance = false, summary = summary)

        assertEquals(3, summary.intervals)
        assertEquals(4, summary.offersReturned)
        assertEquals(1, summary.deferred)
        assertEquals(0, summary.disappeared)
        assertEquals("agencies=0 agencyFailures=0 intervals=3 offersReturned=4 disappeared=0 deferred=1", summary.toLogLine())
    }

    @Test
    fun `a failed week is queued and the remaining weeks of the agency still sync`() {
        val calls = AtomicInteger()
        `when`(client.getFreeYachts(any())).thenAnswer {
            if (calls.incrementAndGet() == 2) throw NauSysRateLimitedException("/freeYachts", 6)
            RestFreeYachtList(status = "OK", freeYachts = emptyList())
        }

        service.syncIntervals(agency, emptyList(), yachtIds, intervals, skipDisappearance = false)

        assertEquals(3, calls.get(), "every interval must be attempted")
        val from: ArgumentCaptor<LocalDate> = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(syncService, times(2)).syncOffers(any(), any(), anyList(), from.captureK(), any(), anyBoolean())
        assertEquals(listOf(intervals[0].start, intervals[2].start), from.allValues)

        val queuedFrom: ArgumentCaptor<LocalDate> = ArgumentCaptor.forClass(LocalDate::class.java)
        val error: ArgumentCaptor<Throwable> = ArgumentCaptor.forClass(Throwable::class.java)
        verify(queue, times(1)).enqueue(anyLong(), queuedFrom.captureK(), any(), anyList(), anyBoolean(), error.captureK())
        assertEquals(intervals[1].start, queuedFrom.value)
        assertTrue(error.value is NauSysRateLimitedException)
    }

    @Test
    fun `drain deletes on success and bumps on failure`() {
        val okRow = row(1, intervals[0])
        val failingRow = row(2, intervals[1])
        `when`(queue.due(500)).thenReturn(listOf(okRow, failingRow))
        `when`(agencyRepository.findById(7L)).thenReturn(Optional.of(agency))
        `when`(yachtRepository.findWithReservationOptionsByAgency(agency)).thenReturn(emptyList())
        `when`(queue.markFailure(any(), any())).thenReturn(false)
        val calls = AtomicInteger()
        `when`(client.getFreeYachts(any())).thenAnswer {
            if (calls.incrementAndGet() == 2) throw NauSysRateLimitedException("/freeYachts", 6)
            RestFreeYachtList(status = "OK", freeYachts = emptyList())
        }

        service.drainRetryQueue()

        verify(syncService, times(1)).syncOffers(any(), any(), anyList(), any(), any(), anyBoolean())
        verify(queue, times(1)).markSuccess(okRow)
        verify(queue, times(1)).markFailure(any(), any())
        verify(queue, never()).markSuccess(failingRow)
    }

    @Test
    fun `drain drops rows whose agency disappeared`() {
        val orphan = row(3, intervals[0]).apply { agencyId = 999 }
        `when`(queue.due(500)).thenReturn(listOf(orphan))
        `when`(agencyRepository.findById(999L)).thenReturn(Optional.empty())

        service.drainRetryQueue()

        verify(client, never()).getFreeYachts(any())
        verify(queue, times(1)).markSuccess(orphan)
    }

    private fun row(
        id: Long,
        interval: SyncInterval,
    ) = NausysOfferSyncRetry().apply {
        this.id = id
        agencyId = 7
        periodFrom = interval.start
        periodTo = interval.end
        yachtExternalIds = "101,102"
        attempts = 1
        createdAt = Instant.now()
        nextAttemptAt = Instant.now()
    }
}
