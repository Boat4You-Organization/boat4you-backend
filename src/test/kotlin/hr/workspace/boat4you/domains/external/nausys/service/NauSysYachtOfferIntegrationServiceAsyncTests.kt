package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.sync.jpa.NausysSearchSyncRetry
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtsSearchRequest
import org.openapitools.client.nausys.model.RestFreeYachtsSearchResponse
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D2/D3 (Mario, 5.9.2026): the search warm propagates partner failures to its callers,
 * and the scheduler-side drain replays parked rows with the identical request.
 */
class NauSysYachtOfferIntegrationServiceAsyncTests {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> eq(value: T): T = ArgumentMatchers.eq(value) ?: value

    @Suppress("UNCHECKED_CAST")
    private fun <T> ArgumentCaptor<T>.captureK(): T = capture() ?: null as T

    private val syncService: NauSysYachtOfferSyncService = mock(NauSysYachtOfferSyncService::class.java)
    private val client: NauSysRetryableClient = mock(NauSysRetryableClient::class.java)
    private val queue: NausysSearchSyncRetryQueue = mock(NausysSearchSyncRetryQueue::class.java)

    private val service = NauSysYachtOfferIntegrationServiceAsync(NauSysAuthProvider("user", "pass"), syncService, client, queue)

    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusDays(10)
    private val ok = RestFreeYachtsSearchResponse(status = "OK", freeYachtsInPeriod = emptyList())

    private fun row(
        id: Long,
        countries: String = "115",
    ) = NausysSearchSyncRetry().apply {
        this.id = id
        periodFrom = from
        periodTo = to.plusDays(id)
        this.countries = countries
        attempts = 1
        createdAt = Instant.now()
        nextAttemptAt = Instant.now()
    }

    /** Fails on the given 1-based call number with [failure], answers OK otherwise. */
    private fun clientFailsOnCall(
        failing: Int,
        failure: Throwable,
    ): AtomicInteger {
        val calls = AtomicInteger()
        `when`(client.getFreeYachtsSearchForAsync(any())).thenAnswer {
            if (calls.incrementAndGet() == failing) throw failure
            ok
        }
        return calls
    }

    @Test
    fun `search warm propagates partner failures instead of swallowing them`() {
        `when`(client.getFreeYachtsSearchForAsync(any())).thenThrow(NauSysRateLimitedException("/freeYachtsSearch", 6))

        assertFailsWith<NauSysRateLimitedException> { service.syncOffersForDateRangeBlocking(from, to, listOf(115L), null, null) }
        verify(syncService, never()).syncOffersForAsync(any())
    }

    @Test
    fun `search warm hands the returned yachts to the sync service`() {
        val yacht = RestFreeYacht(yachtId = 1001L)
        `when`(client.getFreeYachtsSearchForAsync(any())).thenReturn(RestFreeYachtsSearchResponse(status = "OK", freeYachtsInPeriod = listOf(yacht)))

        service.syncOffersForDateRangeBlocking(from, to, listOf(115L), null, null)

        verify(syncService).syncOffersForAsync(listOf(yacht))
    }

    @Test
    fun `drain replays each due row with the parked filter, deletes on success and bumps on failure`() {
        val okRow = row(1)
        val failingRow = row(2, countries = "")
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(listOf(okRow, failingRow))
        `when`(queue.markFailure(any(), any())).thenReturn(false)
        clientFailsOnCall(2, HttpServerErrorException(HttpStatus.BAD_GATEWAY))

        val summary = service.drainSearchRetryQueue()

        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(due = 2, ok = 1, failed = 1), summary)
        verify(queue).markSuccess(okRow)
        verify(queue).markFailure(eq(failingRow), any())

        val requests: ArgumentCaptor<RestFreeYachtsSearchRequest> = ArgumentCaptor.forClass(RestFreeYachtsSearchRequest::class.java)
        verify(client, times(2)).getFreeYachtsSearchForAsync(requests.captureK())
        val first = requests.allValues[0]
        assertEquals(listOf(115L), first.countries)
        assertEquals(null, first.regions)
        assertEquals(null, first.locations)
        assertEquals(true, first.ignoreOptions)
        assertEquals(2000, first.resultsPerPage)
        assertEquals(from, first.periodFrom!!.value)
        assertEquals(okRow.periodTo, first.periodTo!!.value)
        assertEquals(null, requests.allValues[1].countries, "'' must replay as no filter")
    }

    @Test
    fun `drain counts a give-up separately`() {
        val row = row(1)
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(listOf(row))
        `when`(queue.markFailure(any(), any())).thenReturn(true)
        clientFailsOnCall(1, HttpServerErrorException(HttpStatus.BAD_GATEWAY))

        val summary = service.drainSearchRetryQueue()

        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(due = 1, gaveUp = 1), summary)
    }

    @Test
    fun `drain stops after the first 429 so the remaining rows do not burn an attempt`() {
        val rows = listOf(row(1), row(2), row(3))
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(rows)
        `when`(queue.markFailure(any(), any())).thenReturn(false)
        val calls = clientFailsOnCall(2, NauSysRateLimitedException("/freeYachtsSearch", 6))

        val summary = service.drainSearchRetryQueue()

        assertEquals(2, calls.get())
        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(due = 3, ok = 1, failed = 1, notAttempted = 1), summary)
        verify(queue).markSuccess(rows[0])
        verify(queue).markFailure(eq(rows[1]), any())
        verify(queue, never()).markFailure(eq(rows[2]), any())
    }

    @Test
    fun `a non-429 failure does not stop the drain`() {
        val rows = listOf(row(1), row(2), row(3))
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(rows)
        `when`(queue.markFailure(any(), any())).thenReturn(false)
        val calls = clientFailsOnCall(2, HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))

        val summary = service.drainSearchRetryQueue()

        assertEquals(3, calls.get())
        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(due = 3, ok = 2, failed = 1), summary)
    }

    @Test
    fun `drain leaves rows untouched once the wall-clock budget is spent`() {
        val rows = listOf(row(1), row(2))
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(rows)

        val summary = service.drainSearchRetryQueue(budget = Duration.ofMillis(-1))

        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(due = 2, notAttempted = 2), summary)
        verify(client, never()).getFreeYachtsSearchForAsync(any())
        verify(queue, never()).markFailure(any(), any())
        verify(queue, never()).markSuccess(any())
    }

    @Test
    fun `an empty queue is a silent no-op`() {
        `when`(queue.due(NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN)).thenReturn(emptyList())

        val summary = service.drainSearchRetryQueue()

        assertEquals(NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary(), summary)
        assertTrue(summary.due == 0)
        verify(client, never()).getFreeYachtsSearchForAsync(any())
    }
}
