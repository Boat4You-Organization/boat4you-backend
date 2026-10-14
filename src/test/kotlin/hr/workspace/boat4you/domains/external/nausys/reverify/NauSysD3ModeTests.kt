package hr.workspace.boat4you.domains.external.nausys.reverify

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyCounters
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.nausys.service.NauSysD3Run
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferSyncService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestYachtReservationPriceInfo
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B2 "D3" (DESIGN.md): the disappearance pass of [NauSysYachtOfferSyncService.syncOffers] under
 * NAUSYS_D3_MODE. LEGACY (no run) = today's pass; LOG = today's writes plus counts; WRITE = route variants flip at once
 * (flip-logged), unpriced answers strike (day 2 flips), mapping gaps and real holds stay, the marker is never set.
 */
class NauSysD3ModeTests {
    private val day1 = LocalDate.of(2026, 10, 15)
    private val day2 = day1.plusDays(1)
    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusDays(7)

    private val nausys = ExternalSystem().apply { id = 2 }
    private val agency = Agency().apply { id = 7 }
    private val loc10 = Location().apply { id = 10 }
    private val loc11 = Location().apply { id = 11 }

    private fun yacht(id: Long) =
        Yacht().apply {
            this.id = id
            this.agency = this@NauSysD3ModeTests.agency
        }

    private val yachts = (55L..58L).associateWith(::yacht)

    private fun ext(yacht: Long) = 500 + yacht

    // ---- stored offers per yacht (entities the sync mutates)
    private val stored = ConcurrentHashMap<Long, List<Offer>>()

    @Suppress("LongParameterList")
    private fun offer(
        id: Long,
        yacht: Long = 55,
        status: OfferStatus = OfferStatus.FREE,
        ext: String? = "FREE",
        route: Location = loc10,
    ) = Offer()
        .apply {
            this.id = id
            this.yacht = yachts.getValue(yacht)
            dateFrom = from
            dateTo = to
            this.status = status
            extStatus = ext
            locationFrom = route
            locationTo = route
        }.also { o -> stored[yacht] = stored[yacht].orEmpty() + o }

    private fun row(
        yacht: Long = 55,
        status: String = "FREE",
        price: String? = "1000.00",
        loc: Long = 900,
        dateFrom: LocalDate = from,
    ) = RestFreeYacht(
        periodFrom = NauSysDateWrapper(dateFrom.format(NauSysDateWrapper.DATE_FORMATTER)),
        periodTo = NauSysDateWrapper(dateFrom.plusDays(7).format(NauSysDateWrapper.DATE_FORMATTER)),
        yachtId = ext(yacht),
        locationFromId = loc,
        locationToId = loc,
        price = RestYachtReservationPriceInfo(clientPrice = price),
        status = status,
    )

    // ---- repository
    private val saved = CopyOnWriteArrayList<Pair<Long?, OfferStatus?>>()
    private val marks = CopyOnWriteArrayList<List<Any?>>()
    private val counts = CopyOnWriteArrayList<List<Any?>>()
    private val flows = mutableSetOf<Long>()

    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findAllByYachtAndDateFromAndDateTo", "findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual" ->
                    stored[(inv.arguments[0] as Yacht).id].orEmpty()
                "save" -> {
                    val o = inv.arguments[0] as Offer
                    saved += o.id to o.status
                    o
                }
                "markNauSysPeriodUnavailable" -> {
                    marks += inv.arguments.toList()
                    1
                }
                "countNauSysPeriodFlippable" -> {
                    counts += inv.arguments.toList()
                    1
                }
                "existsReservationFlowForOffer" -> inv.arguments[0] in flows
                else -> null
            }
        }

    // ---- evidence store
    private val strikes = ConcurrentHashMap<Long, LocalDate>()
    private val strikeCalls = CopyOnWriteArrayList<List<Any?>>()
    private val checked = CopyOnWriteArrayList<PeriodCheck>()
    private val hidden = CopyOnWriteArrayList<List<Any?>>()
    private val periodChecks =
        mock(PartnerPeriodCheckStore::class.java) { inv ->
            when (inv.method.name) {
                "recordFirstStrike" -> {
                    strikeCalls += inv.arguments.toList()
                    strikes.getOrPut(inv.getArgument(1)) { inv.getArgument(6) }
                }
                "recordCheckedBatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    checked += inv.arguments[1] as List<PeriodCheck>
                    null
                }
                "markHidden" -> {
                    hidden += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }

    private val finished = CopyOnWriteArrayList<List<Any?>>()
    private val runStore =
        mock(ReverifyRunStore::class.java) { inv ->
            when (inv.method.name) {
                "start" -> 41L
                "finish" -> {
                    finished += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }

    private val mappings =
        mock(ExternalMappingService::class.java).also { m ->
            `when`(m.getCachedAllMappingsByTypeAndExtendedType("Yacht", nausys, "Yacht-AgencyId-7"))
                .thenReturn((55L..58L).map { ExternalMapping(ext(it), it, "Yacht", nausys, "Yacht-AgencyId-7") })
            `when`(m.getCachedAllMappingsByType("Location", nausys)).thenReturn(
                listOf(ExternalMapping(900L, 10L, "Location", nausys, null), ExternalMapping(901L, 11L, "Location", nausys, null)),
            )
        }

    private val service =
        NauSysYachtOfferSyncService(
            mappings,
            mock(ExternalSystemService::class.java).also { `when`(it.findById(2L)).thenReturn(nausys) },
            offers,
            mock(LocationQueryingService::class.java).also {
                `when`(it.getCachedLocationById(10L)).thenReturn(loc10)
                `when`(it.getCachedLocationById(11L)).thenReturn(loc11)
            },
            mock(ExtraRepository::class.java),
            mock(OfferExtraRepository::class.java),
            mock(ExternalEquipmentRepository::class.java),
            mock(YachtRepository::class.java).also { r -> yachts.forEach { (id, y) -> `when`(r.findById(id)).thenReturn(Optional.of(y)) } },
            mock(YachtSyncMutex::class.java) { inv ->
                if (inv.method.name == "runExclusiveYachtWrite") {
                    @Suppress("UNCHECKED_CAST")
                    (inv.arguments[1] as () -> Unit).invoke()
                    true
                } else {
                    null
                }
            },
            mock(RealPriceSwitches::class.java).also { `when`(it.extrasZeroOptionalOnSite).thenReturn(RealPriceSwitches.OnOff.OFF) },
            periodChecks,
        )

    private fun run(
        mode: NauSysD3Mode,
        today: LocalDate = day1,
    ): NauSysD3Run? = NauSysD3Run.open(runStore, mode, today)

    private fun sync(
        d3: NauSysD3Run?,
        vararg rows: RestFreeYacht,
    ): Int = service.syncOffers(agency, RestFreeYachtList(status = "OK", freeYachts = rows.toList()), yachts.values.toList(), from, to, false, d3)

    // ---- LEGACY

    @Test
    fun `LEGACY (no run) is today's pass - unpriced and route-variant FREE rows become the SYNTHETIC_DISAPPEARANCE marker, others UNAVAILABLE`() {
        val unpriced = offer(1, yacht = 55)
        val stale = offer(2, yacht = 56, route = loc10)
        val refreshed = offer(3, yacht = 56, route = loc11)
        val option = offer(4, yacht = 56, status = OfferStatus.OPTION, ext = "OPTION", route = loc10).also { it.dateFrom = from }

        val n = sync(null, row(55, price = null), row(56, loc = 901))

        n shouldBe 3
        unpriced.status shouldBe OfferStatus.OPTION_WAITING
        unpriced.extStatus shouldBe NauSysYachtOfferSyncService.SYNTHETIC_DISAPPEARANCE_EXT_STATUS
        stale.status shouldBe OfferStatus.OPTION_WAITING
        refreshed.status shouldBe OfferStatus.FREE
        option.status shouldBe OfferStatus.UNAVAILABLE
        marks.shouldBeEmpty()
        strikeCalls.shouldBeEmpty()
        checked.shouldBeEmpty()
    }

    /**
     * The trace of the pre-real-price pass, recorded by running this exact scenario against b50d5f4 (the J1 commit this
     * branch starts from): every save in order and every row's final status / ext_status.
     */
    private val preRealPriceTrace =
        "n=5 saved=1:OPTION_WAITING,3:FREE,3:FREE,2:OPTION_WAITING,4:UNAVAILABLE,5:UNAVAILABLE,6:OPTION_WAITING " +
            "final=1:OPTION_WAITING:SYNTHETIC_DISAPPEARANCE,2:OPTION_WAITING:SYNTHETIC_DISAPPEARANCE,3:FREE:FREE," +
            "4:UNAVAILABLE:OPTION,5:UNAVAILABLE:UNDER_OPTION,6:OPTION_WAITING:SYNTHETIC_DISAPPEARANCE"

    private fun legacyScenarioTrace(d3: NauSysD3Run?): String {
        val unpriced = offer(1, yacht = 55)
        val stale = offer(2, yacht = 56, route = loc10)
        val refreshed = offer(3, yacht = 56, route = loc11)
        val option = offer(4, yacht = 56, status = OfferStatus.OPTION, ext = "OPTION", route = loc10)
        val under = offer(5, yacht = 57, status = OfferStatus.OPTION_WAITING, ext = "UNDER_OPTION", route = loc10)
        val gap = offer(6, yacht = 58, route = loc10)
        val n = sync(d3, row(55, price = null), row(56, loc = 901), row(57, price = null), row(58, loc = 999))
        return "n=$n saved=" + saved.joinToString(",") { "${it.first}:${it.second}" } + " final=" +
            listOf(unpriced, stale, refreshed, option, under, gap).joinToString(",") { "${it.id}:${it.status}:${it.extStatus}" }
    }

    @Test
    fun `LEGACY regression - the offer writes are byte-identical to the pre-real-price pass`() {
        legacyScenarioTrace(null) shouldBe preRealPriceTrace
        marks.shouldBeEmpty()
        strikeCalls.shouldBeEmpty()
    }

    @Test
    fun `LOG regression - the offer writes are byte-identical to LEGACY, only counts are added`() {
        legacyScenarioTrace(run(NauSysD3Mode.LOG)) shouldBe preRealPriceTrace
        marks.shouldBeEmpty()
        strikeCalls.shouldBeEmpty()
        hidden.shouldBeEmpty()
    }

    // ---- LOG

    @Test
    fun `LOG keeps today's writes and only counts what WRITE would do, per agency and reason`() {
        val unpriced = offer(1, yacht = 55)
        val stale = offer(2, yacht = 56, route = loc10)
        offer(3, yacht = 56, route = loc11)
        val d3 = run(NauSysD3Mode.LOG)!!

        sync(d3, row(55, price = null), row(56, loc = 901))
        d3.finish("nightly")

        unpriced.extStatus shouldBe NauSysYachtOfferSyncService.SYNTHETIC_DISAPPEARANCE_EXT_STATUS
        stale.status shouldBe OfferStatus.OPTION_WAITING
        marks.shouldBeEmpty()
        strikeCalls.shouldBeEmpty()
        counts.single() shouldBe listOf(56L, from, to, 2L, false)
        val delta = finished.single()[1] as ReverifyCounters
        delta.wouldFlipRows shouldBe 1
        delta.firstStrikes shouldBe 1
        delta.calls shouldBe 1
        (finished.single()[2] as String) shouldContain "ROUTE_GONE=1 UNPRICED=1"
    }

    // ---- WRITE

    @Test
    fun `WRITE - a stale route variant flips to UNAVAILABLE at once through the flip-logged guarded query`() {
        val stale = offer(2, yacht = 56, route = loc10)
        val refreshed = offer(3, yacht = 56, route = loc11)
        val d3 = run(NauSysD3Mode.WRITE)!!

        val n = sync(d3, row(56, loc = 901))

        n shouldBe 1
        marks.single() shouldBe listOf(56L, from, to, 2L, false, 41L, ReverifyJobs.NAUSYS_D3, "ROUTE_GONE", null, day1)
        hidden.single().subList(1, 5) shouldBe listOf(56L, from, to, 1)
        stale.status shouldBe OfferStatus.FREE // the entity is not touched; the SQL wrote the flip
        stale.extStatus shouldBe "FREE"
        refreshed.status shouldBe OfferStatus.FREE
    }

    @Test
    fun `WRITE - FREE unpriced is a strike on day 1 with the status unchanged, a later-day second one flips`() {
        val unpriced = offer(1, yacht = 55)
        offer(9, yacht = 57)
        offer(10, yacht = 58)

        sync(run(NauSysD3Mode.WRITE, day1), row(55, price = null), row(57), row(58))

        unpriced.status shouldBe OfferStatus.FREE
        unpriced.extStatus shouldBe "FREE"
        strikeCalls.single() shouldBe listOf(ExternalSystemEnum.NAUSYS, 55L, from, to, PeriodCheckPriority.ON_DEMAND_OR_BOOKING, PeriodCheckResult.UNPRICED, day1)
        marks.shouldBeEmpty()

        sync(run(NauSysD3Mode.WRITE, day2), row(55, price = null), row(57), row(58))

        marks.single() shouldBe listOf(55L, from, to, 1L, false, 41L, ReverifyJobs.NAUSYS_D3, "UNPRICED_TWO_DAY", day1, day2)
        unpriced.status shouldBe OfferStatus.FREE
    }

    @Test
    fun `WRITE - more than half of an answer unpriced records no strikes and escalates the agency`() {
        offer(1, yacht = 55)
        offer(2, yacht = 56)
        offer(3, yacht = 57)
        val d3 = run(NauSysD3Mode.WRITE)!!

        sync(d3, row(55, price = null), row(56, price = "0"), row(57))
        d3.finish("nightly")

        strikeCalls.shouldBeEmpty()
        marks.shouldBeEmpty()
        stored.values.flatten().map { it.status }.toSet() shouldBe setOf(OfferStatus.FREE)
        finished.single()[3] shouldBe listOf(7L)
    }

    @Test
    fun `WRITE - our location mapping gap leaves the row unchanged and is counted`() {
        val row = offer(1, yacht = 55)
        val d3 = run(NauSysD3Mode.WRITE)!!

        sync(d3, row(55, loc = 999))
        d3.finish("nightly")

        row.status shouldBe OfferStatus.FREE
        marks.shouldBeEmpty()
        strikeCalls.shouldBeEmpty()
        (finished.single()[1] as ReverifyCounters).unknown shouldBe 1
        d3.rowsFor(7, NauSysD3Run.Reason.MAPPING_GAP) shouldBe 1
    }

    @Test
    fun `WRITE - a real UNDER_OPTION row and a booking in progress are never flipped, the marker is never set`() {
        val underOption = offer(1, yacht = 56, status = OfferStatus.OPTION_WAITING, ext = "UNDER_OPTION", route = loc10)
        val reserved = offer(2, yacht = 56, status = OfferStatus.RESERVED, ext = "RESERVATION", route = loc10)
        val reservedOther = offer(4, yacht = 56, status = OfferStatus.RESERVED, ext = "RESERVATION", route = loc10)
        offer(3, yacht = 56, route = loc11)
        flows += 2L

        sync(run(NauSysD3Mode.WRITE)!!, row(56, loc = 901))

        underOption.status shouldBe OfferStatus.OPTION_WAITING
        underOption.extStatus shouldBe "UNDER_OPTION"
        reserved.status shouldBe OfferStatus.RESERVED // reservation_flow guard
        reservedOther.status shouldBe OfferStatus.UNAVAILABLE // today's non-FREE branch, guarded
        marks.shouldBeEmpty()
        stored.values.flatten().none { it.extStatus == NauSysYachtOfferSyncService.SYNTHETIC_DISAPPEARANCE_EXT_STATUS } shouldBe true
    }

    @Test
    fun `WRITE - the yacht answered only for other dates is a first strike for the reverifier, no status change`() {
        val row = offer(1, yacht = 55)

        sync(run(NauSysD3Mode.WRITE)!!, row(55, dateFrom = from.plusDays(7)))

        row.status shouldBe OfferStatus.FREE
        strikeCalls.single()[5] shouldBe PeriodCheckResult.EMPTY
        marks.shouldBeEmpty()
    }

    // ---- evidence in every mode

    @Test
    fun `every mode records the rows the partner returns under option as QUOTED_OPTION evidence`() {
        listOf<NauSysD3Mode?>(null, NauSysD3Mode.LOG, NauSysD3Mode.WRITE).forEach { mode ->
            checked.clear()
            stored.clear()
            offer(1, yacht = 55, status = OfferStatus.OPTION_WAITING, ext = "UNDER_OPTION")

            sync(mode?.let { run(it) }, row(55, status = "UNDER_OPTION"), row(56, status = "OPTION"))

            checked.map { it.yachtId to it.result } shouldContainExactly
                listOf(55L to PeriodCheckResult.QUOTED_OPTION, 56L to PeriodCheckResult.QUOTED_OPTION)
            checked.all { it.dateFrom == from && it.dateTo == to } shouldBe true
        }
    }

    @Test
    fun `LEGACY opens no run row`() {
        NauSysD3Run.open(runStore, NauSysD3Mode.LEGACY).shouldBeNull()
        run(NauSysD3Mode.LOG)!!.mode shouldBe NauSysD3Mode.LOG
        org.mockito.Mockito.verify(runStore).start(ReverifyJobs.NAUSYS_D3, ReverifyRunMode.LOG)
    }
}
