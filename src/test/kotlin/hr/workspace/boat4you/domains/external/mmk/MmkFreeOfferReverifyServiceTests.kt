package hr.workspace.boat4you.domains.external.mmk

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.mmk.service.MmkFreeOfferReverifyService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.Offer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The partner is a map yacht -> weeks it still quotes; every other week answers empty, yachts in [failing] throw.
 * The strike table is an in-memory map behind a JdbcTemplate mock; flips are captured as (yacht, weekStart).
 */
class MmkFreeOfferReverifyServiceTests {
    private val day1 = LocalDate.of(2026, 9, 22)
    private val day2 = day1.plusDays(1)

    private val quoted = ConcurrentHashMap<Long, Set<LocalDate>>()

    // periods (from, to) a yacht still quotes — for non-7-night rows, whose start can equal a week's start
    private val quotedPeriods = ConcurrentHashMap<Long, Set<Pair<LocalDate, LocalDate>>>()
    private val failing = ConcurrentHashMap.newKeySet<Long>()
    private val calls = ConcurrentHashMap<String, Int>()
    private val flips = ConcurrentHashMap.newKeySet<Pair<Long, LocalDate>>()
    private val flipPeriods = ConcurrentHashMap.newKeySet<Triple<Long, LocalDate, LocalDate>>()
    private var combos: List<StaleMmkOfferCombo> = emptyList()

    // rows markPeriodUnavailable reports as flipped (0 = an option or our booking arrived since the candidate query)
    private var flipResult = 2

    // key "yacht|from|to" -> (firstEmptyOn, hiddenOn)
    private val strikes = ConcurrentHashMap<String, Pair<LocalDate, LocalDate?>>()

    private fun combo(
        yacht: Long,
        agency: Long,
        start: LocalDate,
        nights: Long = 7,
    ) = object : StaleMmkOfferCombo {
        override val yachtId = yacht
        override val externalYachtId = yacht * 1000
        override val agencyId = agency
        override val dateFrom = start
        override val dateTo = start.plusDays(nights)
    }

    private fun season(
        yacht: Long,
        weeks: Int,
        agency: Long = 1,
        year: Int = 2027,
    ) = (0 until weeks).map { combo(yacht, agency, LocalDate.of(year, 1, 2).plusWeeks(it.toLong())) }

    private val client =
        mock(MmkRetryableClient::class.java) { inv ->
            if (inv.method.name != "getOffers") return@mock null
            val ext = (inv.arguments.first { it is List<*> && (it as List<*>).firstOrNull() is Long } as List<*>).single() as Long
            val from = (inv.arguments[0] as MmkDateTimeWrapper).value!!.toLocalDate()
            val to = (inv.arguments[1] as MmkDateTimeWrapper).value!!.toLocalDate()
            calls.merge("$ext|$from|$to", 1, Int::plus)
            if (ext / 1000 in failing) throw IllegalStateException("MMK 503")
            val weekQuoted = to == from.plusDays(7) && from in (quoted[ext / 1000] ?: emptySet())
            val periodQuoted = (from to to) in (quotedPeriods[ext / 1000] ?: emptySet())
            if (weekQuoted || periodQuoted) listOf(mock(Offer::class.java)) else emptyList<Offer>()
        }
    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findShownFreeMmkCombos" -> combos
                "markPeriodUnavailable" -> {
                    if (flipResult > 0) {
                        flips += inv.getArgument<Long>(0) to inv.getArgument<LocalDate>(1)
                        flipPeriods += Triple(inv.getArgument<Long>(0), inv.getArgument<LocalDate>(1), inv.getArgument<LocalDate>(2))
                    }
                    flipResult
                }
                else -> null
            }
        }
    private val mutex =
        mock(YachtSyncMutex::class.java) { inv ->
            if (inv.method.name == "runExclusiveYachtWrite") {
                (inv.getArgument<() -> Unit>(1)).invoke(); true
            } else {
                null
            }
        }
    private val jdbc =
        mock(JdbcTemplate::class.java) { inv ->
            val sql = inv.arguments[0] as String
            when {
                sql.startsWith("SELECT first_empty_on") -> {
                    val (y, f, t) = listOf(inv.arguments[2], inv.arguments[3], inv.arguments[4])
                    val s = strikes["$y|$f|$t"]
                    if (s == null || s.second != null) emptyList<LocalDate>() else listOf(s.first)
                }
                sql.startsWith("INSERT INTO mmk_free_week_strike") -> {
                    // ON CONFLICT: an open strike stays, a hidden one restarts the cycle
                    val a = inv.arguments.drop(1)
                    strikes.compute("${a[0]}|${a[1]}|${a[2]}") { _, old -> if (old == null || old.second != null) (a[3] as LocalDate) to null else old }; 1
                }
                sql.startsWith("UPDATE mmk_free_week_strike") -> {
                    val a = inv.arguments.drop(1)
                    val k = "${a[2]}|${a[3]}|${a[4]}"
                    strikes[k] = strikes[k]!!.first to (a[0] as LocalDate); 1
                }
                sql.startsWith("DELETE FROM mmk_free_week_strike") -> {
                    val a = inv.arguments.drop(1)
                    strikes.remove("${a[0]}|${a[1]}|${a[2]}"); 1
                }
                else -> if (inv.arguments.getOrNull(1) is RowMapper<*>) emptyList<Any>() else 0
            }
        }
    private val service = MmkFreeOfferReverifyService(offers, client, mutex, jdbc)

    /** Fleet padding so the "too few decided seasons" breaker does not fire in single-yacht scenarios. */
    private fun healthyFleet(from: Long = 100, count: Int = 60): List<StaleMmkOfferCombo> =
        (from until from + count).flatMap { y ->
            quoted[y] = setOf(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 30), LocalDate.of(2027, 2, 27))
            season(y, 9, agency = 900 + y)
        }

    @Test
    fun `a season the partner still quotes is left alone after one probe call`() {
        combos = healthyFleet() + season(1, 40)
        quoted[1] = combos.filter { it.yachtId == 1L }.map { it.dateFrom }.toSet()

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldBeNull()
        flips.isEmpty() shouldBe true
        calls.keys.count { it.startsWith("1000|") } shouldBe 1
    }

    @Test
    fun `a withdrawn season gets a first strike on day one and is hidden only on day two`() {
        combos = healthyFleet() + season(2, 10)

        val r1 = service.reverifyFreeOffers(day1)
        r1.firstStrikes shouldBe 10
        r1.hiddenPeriods shouldBe 0
        flips.isEmpty() shouldBe true

        // same day again (a restart): nothing new, nothing hidden
        service.reverifyFreeOffers(day1).hiddenPeriods shouldBe 0
        flips.isEmpty() shouldBe true

        val r2 = service.reverifyFreeOffers(day2)
        r2.hiddenPeriods shouldBe 10
        r2.hiddenRows shouldBe 20
        flips.map { it.second }.sorted().shouldContainExactly(combos.filter { it.yachtId == 2L }.map { it.dateFrom })
        strikes["2|2027-01-02|2027-01-09"]!!.second shouldBe day2
    }

    @Test
    fun `only the empty weeks of a mixed season are hidden and a quote clears an earlier strike`() {
        combos = healthyFleet() + season(3, 6)
        service.reverifyFreeOffers(day1) // all 6 empty -> 6 first strikes
        strikes.keys.count { it.startsWith("3|") } shouldBe 6

        // agency publishes weeks 1 and 4 overnight (probe samples 0/3/5 stay empty, so step 2 still runs)
        quoted[3] = setOf(combos.first { it.yachtId == 3L && it.dateFrom == LocalDate.of(2027, 1, 9) }.dateFrom, LocalDate.of(2027, 1, 30))
        val r = service.reverifyFreeOffers(day2)

        r.hiddenPeriods shouldBe 4
        flips.map { it.second }.sorted().shouldContainExactly(
            listOf(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 16), LocalDate.of(2027, 1, 23), LocalDate.of(2027, 2, 6)),
        )
        strikes["3|2027-01-09|2027-01-16"].shouldBeNull()
        strikes["3|2027-01-30|2027-02-06"].shouldBeNull()
    }

    @Test
    fun `a season with only long blocks is probed from its own periods and hidden on day two`() {
        // EMA 12210, 26.9.2026: its 2027 weeks were already hidden; 14-, 21- and 28-night rows were left.
        val june12 = LocalDate.of(2027, 6, 12)
        combos = healthyFleet() + listOf(combo(5, 1, june12, 28), combo(5, 1, june12, 21), combo(5, 1, june12.plusWeeks(1), 14))

        service.reverifyFreeOffers(day1).firstStrikes shouldBe 3
        flips.isEmpty() shouldBe true

        service.reverifyFreeOffers(day2).hiddenPeriods shouldBe 3
        flipPeriods.shouldContainExactlyInAnyOrder(
            Triple(5L, june12, june12.plusDays(28)),
            Triple(5L, june12, june12.plusDays(21)),
            Triple(5L, june12.plusWeeks(1), june12.plusWeeks(1).plusDays(14)),
        )
    }

    @Test
    fun `a long block MMK stopped quoting is hidden even while the yacht's weeks still sell`() {
        // Weeks and other lengths are separate groups: a quoted week does not vouch for a phantom 28-night block.
        val jan2 = LocalDate.of(2027, 1, 2)
        combos = healthyFleet() + season(6, 20) + combo(6, 1, jan2, 28)
        quoted[6] = combos.filter { it.yachtId == 6L && it.dateTo == it.dateFrom.plusDays(7) }.map { it.dateFrom }.toSet()

        service.reverifyFreeOffers(day1).firstStrikes shouldBe 1
        service.reverifyFreeOffers(day2).hiddenPeriods shouldBe 1

        flipPeriods.filter { it.first == 6L }.shouldContainExactly(Triple(6L, jan2, jan2.plusDays(28)))
    }

    @Test
    fun `a long block MMK still quotes next to weeks it sells is left alone after one call each`() {
        val jan2 = LocalDate.of(2027, 1, 2)
        combos = healthyFleet() + season(8, 20) + combo(8, 1, jan2, 28)
        quoted[8] = combos.filter { it.yachtId == 8L && it.dateTo == it.dateFrom.plusDays(7) }.map { it.dateFrom }.toSet()
        quotedPeriods[8] = setOf(jan2 to jan2.plusDays(28))

        service.reverifyFreeOffers(day1)
        service.reverifyFreeOffers(day2)

        flipPeriods.none { it.first == 8L } shouldBe true
        strikes.keys.none { it.startsWith("8|") } shouldBe true
        calls.keys.count { it.startsWith("8000|") } shouldBe 2
    }

    @Test
    fun `a hidden period that comes back without a quote starts a new two-day cycle`() {
        combos = healthyFleet() + season(9, 4)
        service.reverifyFreeOffers(day1)
        service.reverifyFreeOffers(day2).hiddenPeriods shouldBe 4
        flips.clear()

        // the agency sweep or an on-demand search shows the same weeks again, MMK still does not quote them
        val day3 = day2.plusDays(1)
        service.reverifyFreeOffers(day3).firstStrikes shouldBe 4
        flips.isEmpty() shouldBe true
        service.reverifyFreeOffers(day3.plusDays(1)).hiddenPeriods shouldBe 4
    }

    @Test
    fun `a period with nothing left to flip clears its strike instead of recording a hide`() {
        combos = healthyFleet() + season(10, 3)
        service.reverifyFreeOffers(day1)
        flipResult = 0 // a partner option or our customer's booking arrived overnight

        val r = service.reverifyFreeOffers(day2)

        r.hiddenPeriods shouldBe 0
        strikes.keys.none { it.startsWith("10|") } shouldBe true
    }

    @Test
    fun `an agency whose long blocks all answer empty is escalated while its weeks are still judged`() {
        // MMK not quoting ANY long period of one agency looks like a call-shape problem, not eight withdrawals
        val jan2 = LocalDate.of(2027, 1, 2)
        combos = healthyFleet() + (1L..8L).flatMap { y -> season(y, 6, agency = 77) + combo(y, 77, jan2, 14) } + season(11, 6, agency = 77)
        (1L..8L).forEach { y -> quoted[y] = combos.filter { it.yachtId == y && it.dateTo == it.dateFrom.plusDays(7) }.map { it.dateFrom }.toSet() }

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldBeNull()
        strikes.keys.none { k -> k.substringBefore('|').toLong() in 1L..8L } shouldBe true
        strikes.keys.count { it.startsWith("11|") } shouldBe 6
    }

    @Test
    fun `non-weekly groups are skipped when their fleet-wide empty share is implausible, weeks are still judged`() {
        val jan2 = LocalDate.of(2027, 1, 2)
        val longOnes = (200L until 260L).map { y -> combo(y, 2000 + y, jan2, 14) }
        (200L until 250L).forEach { y -> quotedPeriods[y] = setOf(jan2 to jan2.plusDays(14)) } // 10 of 60 empty = 17 %
        combos = healthyFleet() + longOnes + season(12, 5, agency = 12)

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldBeNull()
        strikes.keys.none { k -> k.substringBefore('|').toLong() in 250L until 260L } shouldBe true
        strikes.keys.count { it.startsWith("12|") } shouldBe 5
    }

    @Test
    fun `in an unquoted season a long block MMK still quotes keeps its row`() {
        val jan2 = LocalDate.of(2027, 1, 2)
        combos = healthyFleet() + season(7, 6) + combo(7, 1, jan2, 14)
        quotedPeriods[7] = setOf(jan2 to jan2.plusDays(14))

        service.reverifyFreeOffers(day1)
        service.reverifyFreeOffers(day2)

        flipPeriods.filter { it.first == 7L }.size shouldBe 6
        (Triple(7L, jan2, jan2.plusDays(14)) in flipPeriods) shouldBe false
    }

    @Test
    fun `a call failure is unknown and never becomes a strike`() {
        combos = healthyFleet() + season(4, 8)
        failing += 4

        service.reverifyFreeOffers(day1).firstStrikes shouldBe 0
        strikes.keys.none { it.startsWith("4|") } shouldBe true
    }

    @Test
    fun `an agency whose whole fleet answers empty is escalated, not hidden`() {
        // 8 of 108 seasons empty (7 %) is a normal fleet-wide day, but 8 of 8 for ONE agency is not a decision for a job
        combos = healthyFleet(count = 100) + (1L..8L).flatMap { season(it, 6, agency = 77) }

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldBeNull()
        r.firstStrikes shouldBe 0
        strikes.keys.none { k -> k.substringBefore('|').toLong() in 1L..8L } shouldBe true
    }

    @Test
    fun `the run aborts without writing when the fleet-wide empty share is implausible`() {
        combos = healthyFleet(count = 60) + (1L..20L).flatMap { season(it, 3, agency = 500 + it) } // 20 / 80 = 25 %

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldNotBeNull() shouldContain "outage"
        strikes.isEmpty() shouldBe true
    }

    @Test
    fun `the run aborts when the partner is mostly unreachable even if the few answers look fine`() {
        combos = healthyFleet(count = 60) + (1L..30L).flatMap { season(it, 3, agency = 500 + it) }
        (1L..30L).forEach { failing += it }

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldNotBeNull() shouldContain "degraded"
    }

    @Test
    fun `the run aborts when almost nothing could be decided`() {
        combos = healthyFleet(count = 30) + season(1, 3)
        service.abortReason(total = 31, decided = 31, unknown = 0, emptyShare = 0.03).shouldNotBeNull() shouldContain "unreachable"
        service.abortReason(total = 6929, decided = 6900, unknown = 29, emptyShare = 0.03).shouldBeNull()
    }
}
