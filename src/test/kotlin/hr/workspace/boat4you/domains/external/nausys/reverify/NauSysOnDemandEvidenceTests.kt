package hr.workspace.boat4you.domains.external.nausys.reverify

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.NauSysShownRow
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.nausys.service.NauSysD3Run
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferSyncService
import hr.workspace.boat4you.domains.external.nausys.service.NausysOfferSyncRetryQueue
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestYachtReservationPriceInfo
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B2 path 4 (DESIGN.md): the NauSys per-yacht on-demand warm under NAUSYS_REVERIFY, and the D3 run a grid
 * pass opens under NAUSYS_D3_MODE. Strikes only, never a status write from the request path.
 */
class NauSysOnDemandEvidenceTests {
    private val from = LocalDate.of(2027, 7, 3)
    private val to = from.plusDays(7)
    private val ext = 501L
    private val nausys = ExternalSystem().apply { id = 2 }

    private var reverify = NauSysReverifyMode.LOG
    private var d3Mode = NauSysD3Mode.LEGACY
    private val switches =
        mock(RealPriceSwitches::class.java) { inv ->
            when (inv.method.name) {
                "getNausysReverify" -> reverify
                "getNausysD3Mode" -> d3Mode
                else -> null
            }
        }

    private var answer = RestFreeYachtList(status = "OK", freeYachts = emptyList())
    private val client = mock(NauSysRetryableClient::class.java) { inv -> if (inv.method.name == "getFreeYachts") answer else null }

    private val upserts = CopyOnWriteArrayList<List<RestFreeYacht>>()
    private val d3Runs = CopyOnWriteArrayList<NauSysD3Run?>()
    private val offerSync =
        mock(NauSysYachtOfferSyncService::class.java) { inv ->
            when (inv.method.name) {
                "syncOffersForAsync" -> {
                    @Suppress("UNCHECKED_CAST")
                    upserts += inv.arguments[0] as List<RestFreeYacht>
                    null
                }
                "syncOffers" -> {
                    d3Runs += inv.arguments[6] as NauSysD3Run?
                    0
                }
                else -> null
            }
        }

    private var shown: List<NauSysShownRow> = listOf(shownRow(10, 10))
    private val writes = CopyOnWriteArrayList<String>()
    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findNauSysShownPeriodRows" -> shown
                else -> {
                    writes += inv.method.name
                    null
                }
            }
        }

    private fun shownRow(
        locFrom: Long,
        locTo: Long,
    ) = object : NauSysShownRow {
        override val offerId = 9001L
        override val yachtId = 55L
        override val locationFromId = locFrom
        override val locationToId = locTo
    }

    private val strikes = CopyOnWriteArrayList<List<Any?>>()
    private val periodChecks =
        mock(PartnerPeriodCheckStore::class.java) { inv ->
            if (inv.method.name == "recordFirstStrike") {
                strikes += inv.arguments.toList()
                inv.getArgument<LocalDate>(6)
            } else {
                null
            }
        }

    private val runStarts = CopyOnWriteArrayList<List<Any?>>()
    private val runStore =
        mock(ReverifyRunStore::class.java) { inv ->
            if (inv.method.name == "start") {
                runStarts += inv.arguments.toList()
                5L
            } else {
                null
            }
        }

    private val agency = Agency().apply { id = 7 }
    private val agencies = mock(AgencyRepository::class.java)

    private val service =
        NauSysYachtOfferIntegrationService(
            agencies,
            mock(YachtRepository::class.java),
            mock(ExternalMappingService::class.java).also {
                `when`(it.getCachedAllMappingsByType("Location", nausys)).thenReturn(
                    listOf(ExternalMapping(900L, 10L, "Location", nausys, null), ExternalMapping(901L, 11L, "Location", nausys, null)),
                )
            },
            NauSysAuthProvider("u", "p"),
            mock(ExternalSystemService::class.java).also { `when`(it.findById(2L)).thenReturn(nausys) },
            offerSync,
            mock(SyncConfigurationProperties::class.java),
            client,
            mock(NausysOfferSyncRetryQueue::class.java),
            switches,
            runStore,
            offers,
            periodChecks,
            mock(PlatformTransactionManager::class.java),
        )

    private fun row(
        status: String = "FREE",
        price: String? = "900.00",
        loc: Long = 900,
    ) = RestFreeYacht(
        periodFrom = NauSysDateWrapper(from.format(NauSysDateWrapper.DATE_FORMATTER)),
        periodTo = NauSysDateWrapper(to.format(NauSysDateWrapper.DATE_FORMATTER)),
        yachtId = ext,
        locationFromId = loc,
        locationToId = loc,
        price = RestYachtReservationPriceInfo(clientPrice = price),
        status = status,
    )

    @Test
    fun `OFF is today - a non-OK body with rows is still upserted and nothing is recorded`() {
        reverify = NauSysReverifyMode.OFF
        answer = RestFreeYachtList(status = "ERROR", freeYachts = listOf(row()))

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        upserts.size shouldBe 1
        strikes.shouldBeEmpty()
    }

    @Test
    fun `a non-OK body is unknown - nothing upserted, nothing recorded`() {
        answer = RestFreeYachtList(status = "ERROR", freeYachts = listOf(row()))

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        upserts.shouldBeEmpty()
        strikes.shouldBeEmpty()
    }

    @Test
    fun `OK and empty for a period we show is a priority-3 strike, never a status write`() {
        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        strikes.single() shouldBe listOf(ExternalSystemEnum.NAUSYS, 55L, from, to, PeriodCheckPriority.ON_DEMAND_OR_BOOKING, PeriodCheckResult.EMPTY, LocalDate.now())
        writes.shouldBeEmpty()
    }

    @Test
    fun `OK and FREE without a price is an UNPRICED strike`() {
        answer = RestFreeYachtList(status = "OK", freeYachts = listOf(row(price = null)))

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        strikes.single()[5] shouldBe PeriodCheckResult.UNPRICED
        writes.shouldBeEmpty()
    }

    @Test
    fun `OK quoted only on another route records ROUTE_GONE, the same route records nothing`() {
        answer = RestFreeYachtList(status = "OK", freeYachts = listOf(row(loc = 901)))
        service.syncOffersForYachtIdAndDateRage(ext, from, to)
        strikes.single()[5] shouldBe PeriodCheckResult.ROUTE_GONE
        upserts.size shouldBe 1

        strikes.clear()
        answer = RestFreeYachtList(status = "OK", freeYachts = listOf(row(loc = 900)))
        service.syncOffersForYachtIdAndDateRage(ext, from, to)
        strikes.shouldBeEmpty()
    }

    @Test
    fun `no evidence when we show no row with exactly these dates`() {
        shown = emptyList()

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        strikes.shouldBeEmpty()
    }

    @Test
    fun `a grid pass opens a NAUSYS_D3 run under LOG and hands it to syncOffers, LEGACY opens none`() {
        `when`(agencies.findAllActiveByPrimarySyncProviderAndHasYacht(2L)).thenReturn(setOf(agency))
        d3Mode = NauSysD3Mode.LOG

        service.yachtOfferSync()

        runStarts.single() shouldBe listOf(ReverifyJobs.NAUSYS_D3, ReverifyRunMode.LOG)

        runStarts.clear()
        d3Mode = NauSysD3Mode.LEGACY
        service.yachtOfferSync()
        runStarts.shouldBeEmpty()
    }

    @Test
    fun `the D3 run reaches syncOffers through the grid`() {
        val run = NauSysD3Run.open(runStore, NauSysD3Mode.WRITE).shouldNotBeNull()
        answer = RestFreeYachtList(status = "OK", freeYachts = emptyList())

        service.syncIntervals(agency, emptyList(), listOf(ext), listOf(hr.workspace.boat4you.domains.external.model.SyncInterval(from, to)), false, d3 = run)

        d3Runs.single() shouldBe run
    }
}
