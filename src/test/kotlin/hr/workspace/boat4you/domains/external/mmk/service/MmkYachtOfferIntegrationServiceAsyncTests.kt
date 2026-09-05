package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ReservationOption
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper.Companion.READ_FORMATTER
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync.Companion.fullHorizonWindows
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync.Companion.nearTermWindow
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.openapitools.client.mmk.model.Flexibility
import org.openapitools.client.mmk.model.Offer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constructs the service directly, so `@Async` is bypassed and the sweep runs synchronously.
 * `getOffersForAsync` has 26 parameters — its calls are recorded through a default [Answer]
 * instead of a 26-matcher `verify`.
 */
class MmkYachtOfferIntegrationServiceAsyncTests {
    private val yachtRepository: YachtRepository = mock(YachtRepository::class.java)
    private val externalMappingService: ExternalMappingService = mock(ExternalMappingService::class.java)
    private val externalSystemService: ExternalSystemService = mock(ExternalSystemService::class.java)
    private val syncService: MmkYachtOfferSyncService = mock(MmkYachtOfferSyncService::class.java)

    private val recordedCalls = mutableListOf<Array<Any?>>()
    private var partnerResponse: (Array<Any?>) -> List<Offer> = { emptyList() }
    private val client: MmkRetryableClient =
        mock(
            MmkRetryableClient::class.java,
            Answer<Any?> { invocation ->
                if (invocation.method.name == "getOffersForAsync") {
                    recordedCalls.add(invocation.arguments)
                    partnerResponse(invocation.arguments)
                } else {
                    null
                }
            },
        )

    private val service =
        MmkYachtOfferIntegrationServiceAsync(
            yachtRepository,
            externalMappingService,
            externalSystemService,
            SyncConfigurationProperties(offerMaxYears = 1, minDurationDays = 3),
            syncService,
            mock(MmkOfferIntegrationUtils::class.java),
            client,
        )

    private val mmk = ExternalSystem().apply { id = 1 }
    private val agency = Agency().apply { id = 7 }
    private val agencyExternalId = 555L
    private val yachtExternalId = 9001L
    private val today = LocalDate.of(2026, 9, 5)
    private val upserts = mutableListOf<List<Offer>>()

    @BeforeTest
    fun stubOneSaturdayYacht() {
        `when`(externalSystemService.findById(1L)).thenReturn(mmk)
        val yacht =
            Yacht().apply {
                id = 55
                reservationOptions =
                    mutableSetOf(
                        ReservationOption().apply {
                            dateFrom = today.minusDays(30)
                            dateTo = today.plusDays(400)
                            minimalDuration = 7
                            checkinSat = true
                            checkoutSat = true
                        },
                    )
            }
        `when`(yachtRepository.findWithReservationOptionsByAgency(agency)).thenReturn(listOf(yacht))
        `when`(externalMappingService.getCachedAllMappingsByTypeAndExtendedType("Yacht", mmk, YACHT_AGENCY_EXTERNAL_MAPPING_KEY + 7))
            .thenReturn(listOf(ExternalMapping(yachtExternalId, 55L, "Yacht", mmk, YACHT_AGENCY_EXTERNAL_MAPPING_KEY + 7)))
        `when`(syncService.syncOffersForAgency(anyLong(), anyList())).thenAnswer {
            upserts.add(it.getArgument(1))
            MmkOfferUpsertCounters().apply { upserted = it.getArgument<List<Offer>>(1).size }
        }
    }

    private fun offer(
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) = Offer(
        yachtId = yachtExternalId,
        yacht = "Test Yacht",
        startBaseId = 1,
        endBaseId = 1,
        startBase = "Split",
        endBase = "Split",
        dateFrom = MmkDateTimeWrapper(dateFrom.atStartOfDay().format(READ_FORMATTER)),
        dateTo = MmkDateTimeWrapper(dateTo.atStartOfDay().format(READ_FORMATTER)),
        product = "Bareboat",
        price = 1000.0,
        currency = "EUR",
        startPrice = 1000.0,
        discountPercentage = 0f,
    )

    private fun Array<Any?>.dateFrom() = (this[0] as MmkDateTimeWrapper).value!!.format(READ_FORMATTER)

    private fun Array<Any?>.dateTo() = (this[1] as MmkDateTimeWrapper).value!!.format(READ_FORMATTER)

    @Test
    fun `fullHorizonWindows reproduces the nightly month-anchored one-year slices`() {
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 1)..LocalDate.of(2027, 8, 31),
                LocalDate.of(2027, 9, 1)..LocalDate.of(2028, 8, 31),
            ),
            fullHorizonWindows(today, 1),
        )
    }

    @Test
    fun `nearTermWindow spans today to today plus 84 days`() {
        val window = nearTermWindow(today)
        assertEquals(today, window.start)
        assertEquals(LocalDate.of(2026, 11, 28), window.endInclusive)
    }

    @Test
    fun `near-term window makes one flexibility-6 call per group bounded to today plus 84 days and clips the upsert`() {
        val inside = offer(today.plusDays(7), today.plusDays(14))
        val outside = offer(today.plusDays(91), today.plusDays(98))
        partnerResponse = { listOf(inside, outside) }

        val result = service.syncOffersForAgencyYachts(agency, agencyExternalId, listOf(nearTermWindow(today)), clipToWindow = true).get()

        val call = recordedCalls.single()
        assertEquals("2026-09-05 00:00:00", call.dateFrom())
        assertEquals("2026-11-28 23:59:59", call.dateTo())
        assertEquals(Flexibility._6, call[2])
        assertEquals(listOf(agencyExternalId), call[3])
        assertEquals(listOf(yachtExternalId), call[9])
        assertEquals(listOf(listOf(inside)), upserts, "offers starting outside the window must not be upserted")
        assertEquals(1, result.calls)
        assertEquals(0, result.failures)
        assertEquals(1, result.upsert.upserted)
    }

    @Test
    fun `nightly windows make the two month-anchored calls and pass the response through unclipped`() {
        val early = offer(today.plusDays(7), today.plusDays(14))
        val late = offer(today.plusDays(500), today.plusDays(507))
        partnerResponse = { listOf(early, late) }

        val result = service.syncOffersForAgencyYachts(agency, agencyExternalId, fullHorizonWindows(today, 1), clipToWindow = false).get()

        assertEquals(listOf("2026-09-01 00:00:00", "2027-09-01 00:00:00"), recordedCalls.map { it.dateFrom() })
        assertEquals(listOf("2027-08-31 23:59:59", "2028-08-31 23:59:59"), recordedCalls.map { it.dateTo() })
        assertTrue(recordedCalls.all { it[2] == Flexibility._6 })
        assertEquals(listOf(listOf(early, late), listOf(early, late)), upserts)
        assertEquals(2, result.calls)
        assertEquals(4, result.upsert.upserted)
    }

    @Test
    fun `an upsert failure is counted and the remaining windows still sync`() {
        partnerResponse = { listOf(offer(today.plusDays(7), today.plusDays(14))) }
        val attempts = AtomicInteger()
        `when`(syncService.syncOffersForAgency(anyLong(), anyList())).thenAnswer {
            if (attempts.incrementAndGet() == 1) error("db")
            MmkOfferUpsertCounters().apply { upserted = 3 }
        }

        val result = service.syncOffersForAgencyYachts(agency, agencyExternalId, fullHorizonWindows(today, 1), clipToWindow = false).get()

        assertEquals(2, recordedCalls.size)
        assertEquals(2, result.calls)
        assertEquals(1, result.failures)
        assertEquals(3, result.upsert.upserted)
    }

    @Test
    fun `a 400 from MMK is an expected skip, not a failure`() {
        partnerResponse = {
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null)
        }

        val result = service.syncOffersForAgencyYachts(agency, agencyExternalId, listOf(nearTermWindow(today)), clipToWindow = true).get()

        assertEquals(1, result.calls)
        assertEquals(0, result.failures)
        assertTrue(upserts.isEmpty())
    }
}
