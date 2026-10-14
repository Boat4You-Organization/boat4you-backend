package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.OnOff
import hr.workspace.boat4you.common.config.RealPriceSwitches.SearchPredicate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DESIGN.md WS-A test 13: the native fragments shared by the facets and the relax hint, rendered. */
class SearchAvailabilitySqlTest {
    private val start: LocalDate = LocalDate.of(2031, 9, 6)

    @Test
    fun `the floor is 300 EUR a week compared per day, in one place`() {
        assertEquals(BigDecimal("42.8571428571"), SearchAvailabilitySql.MIN_WEEK_PRICE_PER_DAY)
        // 300.00 a week (the matview divides the offer total by its nights) passes, 299.99 does not
        assertTrue(BigDecimal("300.00").divide(BigDecimal(7), 16, java.math.RoundingMode.HALF_UP) >= SearchAvailabilitySql.MIN_WEEK_PRICE_PER_DAY)
        assertFalse(BigDecimal("299.99").divide(BigDecimal(7), 16, java.math.RoundingMode.HALF_UP) >= SearchAvailabilitySql.MIN_WEEK_PRICE_PER_DAY)
        assertEquals("(date_from IS NULL OR client_price >= :priceFloor)", SearchAvailabilitySql.FLOOR_CLAUSE)
    }

    @Test
    fun `shift and week starts`() {
        assertEquals(3, SearchAvailabilitySql.shiftDays(7))
        assertEquals(2, SearchAvailabilitySql.shiftDays(3))
        assertEquals(emptyList(), SearchAvailabilitySql.weekStarts(start, 7))
        assertEquals(emptyList(), SearchAvailabilitySql.weekStarts(start, 10))
        assertEquals(listOf(start, start.plusDays(7)), SearchAvailabilitySql.weekStarts(start, 14))
        assertEquals(4, SearchAvailabilitySql.weekStarts(start, 28).size)
    }

    @Test
    fun `mode - defaults are LEGACY, admins never get D1 or the floor, sitemap requests skip the floor`() {
        val on = RealPriceSwitches.Snapshot(searchPredicate = SearchPredicate.D1, searchPriceFloor = OnOff.ON)
        assertEquals(SearchAvailabilitySql.Mode.LEGACY, SearchAvailabilitySql.Mode.of(RealPriceSwitches.Snapshot()))
        assertEquals(SearchAvailabilitySql.Mode(d1 = true, floor = true), SearchAvailabilitySql.Mode.of(on))
        assertEquals(SearchAvailabilitySql.Mode.LEGACY, SearchAvailabilitySql.Mode.of(on, legacyAvailability = true))
        assertEquals(SearchAvailabilitySql.Mode(d1 = true, floor = false), SearchAvailabilitySql.Mode.of(on, sitemapRequest = true))
    }

    @Test
    fun `hull hard block - the row's own window and the request, the zombie escape`() {
        val sql = SearchAvailabilitySql.hullHardBlock()
        assertTrue("er.date_from < GREATEST(:hardBlockEnd, yacht_search_view.date_to)" in sql, sql)
        assertTrue("er.date_to > LEAST(:hardBlockStart, yacht_search_view.date_from)" in sql, sql)
        assertTrue("er.status IN ('RESERVATION', 'SERVICE')" in sql, sql)
        assertTrue("(er.option_expiration IS NULL OR er.option_expiration > :zombieCutoff)" in sql, sql)
        assertTrue("er.yacht_id = v.id" in SearchAvailabilitySql.hullHardBlock("v"))
    }

    @Test
    fun `requested period taken - occupancy evidence only, twin guard`() {
        val sql = SearchAvailabilitySql.requestedPeriodTaken()
        assertTrue("b.yacht_id = yacht_search_view.id" in sql, sql)
        assertTrue("(b.status IN ('RESERVED', 'SERVICE') AND b.date_from < :hardBlockEnd AND b.date_to > :hardBlockStart)" in sql, sql)
        assertTrue("b.status = 'UNAVAILABLE' AND b.date_from = :hardBlockStart AND b.date_to = :hardBlockEnd" in sql, sql)
        assertTrue("b.ext_status IN ('6', '10', '11')" in sql, sql)
        // A two-strike hide record is "no longer quoted", not occupancy: it never makes the period taken.
        assertFalse("mmk_free_week_strike" in sql || "partner_period_check" in sql || "hidden_on" in sql, sql)
        assertTrue("f.status NOT IN ('RESERVED', 'SERVICE', 'UNAVAILABLE')" in sql, sql)
        assertFalse("{" in sql, sql)
    }

    @Test
    fun `positional form - one argument per question mark, in order`() {
        val positional = SearchAvailabilitySql.requestedPeriodTakenPositional()
        assertEquals(positional.pattern.count { it == '?' }, positional.args.size)
        assertEquals(listOf("yacht", "end", "start", "start", "end"), positional.args)
        assertEquals(
            SearchAvailabilitySql.requestedPeriodTaken("X"),
            positional.args.fold(positional.pattern) { sql, arg ->
                sql.replaceFirst("?", if (arg == "yacht") "X.id" else ":hardBlock${arg.replaceFirstChar { it.uppercase() }}")
            },
        )
    }

    @Test
    fun `D1 dated clauses - the searched length within the shift, a floored tiling over the caller's row filters`() {
        val rowVarying = listOf("offer_status <> 'UNAVAILABLE'", "client_price <= :maxPriceDaily")
        val week = SearchAvailabilitySql.d1Dated(start, start.plusDays(7), rowVarying, LocalDateTime.MIN)
        assertEquals(3, week.clauses.size)
        assertTrue("(number_of_days = :d1Nights AND date_from BETWEEN :d1ShiftFrom AND :d1ShiftTo)" in week.clauses[0])
        assertFalse("d1WeekStarts" in week.clauses[0], "a 7-night request has no tiling")
        assertEquals(setOf("d1Nights", "d1ShiftFrom", "d1ShiftTo", "hardBlockStart", "hardBlockEnd", "zombieCutoff"), week.params.keys)
        assertEquals(start.minusDays(3), week.params["d1ShiftFrom"])
        assertEquals(start.plusDays(3), week.params["d1ShiftTo"])
        assertTrue(week.clauses[1].startsWith("(yacht_search_view.date_from IS NULL OR NOT EXISTS"))
        assertTrue(week.clauses[2].startsWith("NOT EXISTS (SELECT 1 FROM offer b"))

        val twoWeeks = SearchAvailabilitySql.d1Dated(start, start.plusDays(14), rowVarying, LocalDateTime.MIN)
        val tile = twoWeeks.clauses[0]
        assertTrue("number_of_days = 7 AND date_from IN (:d1WeekStarts) AND client_price >= :priceFloor AND id IN (" in tile, tile)
        assertTrue(
            "SELECT t.id FROM yacht_search_view t WHERE t.number_of_days = 7 AND t.date_from IN (:d1WeekStarts) " +
                "AND t.client_price >= :priceFloor AND offer_status <> 'UNAVAILABLE' AND client_price <= :maxPriceDaily " +
                "GROUP BY t.id HAVING COUNT(DISTINCT t.date_from) = :d1Weeks" in tile,
            tile,
        )
        assertEquals(listOf(start, start.plusDays(7)), twoWeeks.params["d1WeekStarts"])
        assertEquals(2, twoWeeks.params["d1Weeks"])
        assertEquals(SearchAvailabilitySql.MIN_WEEK_PRICE_PER_DAY, twoWeeks.params["priceFloor"])
    }
}
