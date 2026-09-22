package hr.workspace.boat4you.domains.external.mmk

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.mmk.service.MmkFreeOfferReverifyService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import io.kotest.matchers.collections.shouldContainExactly
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
    private val failing = ConcurrentHashMap.newKeySet<Long>()
    private val calls = ConcurrentHashMap<String, Int>()
    private val flips = ConcurrentHashMap.newKeySet<Pair<Long, LocalDate>>()
    private var combos: List<StaleMmkOfferCombo> = emptyList()

    // key "yacht|from|to" -> (firstEmptyOn, hiddenOn)
    private val strikes = ConcurrentHashMap<String, Pair<LocalDate, LocalDate?>>()

    private fun combo(
        yacht: Long,
        agency: Long,
        start: LocalDate,
    ) = object : StaleMmkOfferCombo {
        override val yachtId = yacht
        override val externalYachtId = yacht * 1000
        override val agencyId = agency
        override val dateFrom = start
        override val dateTo = start.plusDays(7)
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
            calls.merge("$ext|$from", 1, Int::plus)
            if (ext / 1000 in failing) throw IllegalStateException("MMK 503")
            if (from in (quoted[ext / 1000] ?: emptySet())) listOf(mock(Offer::class.java)) else emptyList<Offer>()
        }
    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findFreeWeeklyMmkCombos" -> combos
                "markWeekUnavailable" -> {
                    flips += inv.getArgument<Long>(0) to inv.getArgument<LocalDate>(1); 2
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
                    val a = inv.arguments.drop(1)
                    strikes.putIfAbsent("${a[0]}|${a[1]}|${a[2]}", (a[3] as LocalDate) to null); 1
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
        r1.hiddenWeeks shouldBe 0
        flips.isEmpty() shouldBe true

        // same day again (a restart): nothing new, nothing hidden
        service.reverifyFreeOffers(day1).hiddenWeeks shouldBe 0
        flips.isEmpty() shouldBe true

        val r2 = service.reverifyFreeOffers(day2)
        r2.hiddenWeeks shouldBe 10
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

        r.hiddenWeeks shouldBe 4
        flips.map { it.second }.sorted().shouldContainExactly(
            listOf(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 16), LocalDate.of(2027, 1, 23), LocalDate.of(2027, 2, 6)),
        )
        strikes["3|2027-01-09|2027-01-16"].shouldBeNull()
        strikes["3|2027-01-30|2027-02-06"].shouldBeNull()
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
