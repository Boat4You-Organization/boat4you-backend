package hr.workspace.boat4you.domains.external.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1 (Mario, 5.9.2026): a search whose range is a multiple of 7 days never warms a
 * partner — the nightly grid already stores it — regardless of the start weekday.
 */
class SearchWarmPolicyTests {
    private val anchor = LocalDate.of(2027, 6, 5) // a Saturday

    @Test
    fun `7, 14, 21 and 28 day ranges are weekly from every start day of the week`() {
        for (day in DayOfWeek.entries) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (weeks in 1..4) {
                val end = start.plusWeeks(weeks.toLong())
                assertTrue(SearchWarmPolicy.isWeeklyRange(start, end), "$start → $end ($weeks w) must be weekly")
                assertFalse(SearchWarmPolicy.shouldWarm(start, end), "$start → $end must not warm")
            }
        }
    }

    @Test
    fun `odd length ranges still warm`() {
        for (days in listOf(3L, 4L, 5L, 6L, 8L, 10L, 13L, 15L, 20L, 27L)) {
            val end = anchor.plusDays(days)
            assertFalse(SearchWarmPolicy.isWeeklyRange(anchor, end), "$days days must not be weekly")
            assertTrue(SearchWarmPolicy.shouldWarm(anchor, end), "$days days must warm")
        }
    }

    @Test
    fun `weekly detection is a pure day count, also across month and year boundaries`() {
        assertTrue(SearchWarmPolicy.isWeeklyRange(LocalDate.of(2027, 12, 28), LocalDate.of(2028, 1, 4)))
        assertTrue(SearchWarmPolicy.isWeeklyRange(LocalDate.of(2028, 2, 26), LocalDate.of(2028, 3, 11))) // leap February, 14 d
        assertFalse(SearchWarmPolicy.isWeeklyRange(LocalDate.of(2027, 12, 28), LocalDate.of(2028, 1, 5)))
    }

    @Test
    fun `a zero length range counts as weekly (the controller rejects it before the policy anyway)`() {
        assertTrue(SearchWarmPolicy.isWeeklyRange(anchor, anchor))
    }

    @Test
    fun `ranges that already started never warm, weekly or not`() {
        val today = LocalDate.of(2026, 9, 5)
        // 5-day and 12-day ranges from the past: nothing left to sell, nothing to refresh.
        assertTrue(SearchWarmPolicy.isPast(today.minusDays(1), today))
        assertFalse(SearchWarmPolicy.shouldWarm(today.minusDays(30), today.minusDays(25), today))
        assertFalse(SearchWarmPolicy.shouldWarm(today.minusDays(1), today.plusDays(11), today))
        // Starting today or later is fine for a non-weekly range …
        assertFalse(SearchWarmPolicy.isPast(today, today))
        assertTrue(SearchWarmPolicy.shouldWarm(today, today.plusDays(12), today))
        assertTrue(SearchWarmPolicy.shouldWarm(today.plusDays(60), today.plusDays(65), today))
        // … and a weekly range in the future still does not warm (D1).
        assertFalse(SearchWarmPolicy.shouldWarm(today.plusDays(7), today.plusDays(14), today))
    }

    // ---- B3: per-partner rule, NAUSYS_WARM_LONG_RANGES ----

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun `switch ON - Sat to Sat 7 nights is never warmed for NauSys`() {
        assertFalse(SearchWarmPolicy.shouldWarmNauSys(anchor, anchor.plusDays(7), longRanges = true, today = today))
        assertTrue(SearchWarmPolicy.isSaturdayWeek(anchor, anchor.plusDays(7)))
    }

    @Test
    fun `switch ON - 14, 21, 28 Sat to Sat and every non-Saturday weekly range warm NauSys`() {
        for (weeks in 2L..4L) {
            assertTrue(SearchWarmPolicy.shouldWarmNauSys(anchor, anchor.plusWeeks(weeks), true, today), "Sat→Sat $weeks w")
        }
        for (day in DayOfWeek.entries.filter { it != DayOfWeek.SATURDAY }) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (weeks in 1L..4L) {
                assertTrue(SearchWarmPolicy.shouldWarmNauSys(start, start.plusWeeks(weeks), true, today), "$day $weeks w")
            }
        }
        val wednesday = LocalDate.of(2027, 6, 9)
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        assertTrue(SearchWarmPolicy.shouldWarmNauSys(wednesday, wednesday.plusDays(7), true, today))
        assertFalse(SearchWarmPolicy.isSaturdayWeek(wednesday, wednesday.plusDays(7)))
    }

    @Test
    fun `switch ON - odd length ranges still warm NauSys, from any day`() {
        for (day in DayOfWeek.entries) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (days in listOf(3L, 5L, 8L, 10L, 13L, 27L)) {
                assertTrue(SearchWarmPolicy.shouldWarmNauSys(start, start.plusDays(days), true, today), "$day +$days")
            }
        }
    }

    @Test
    fun `switch OFF - NauSys is exactly today's rule for every start day and length`() {
        for (day in DayOfWeek.entries) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (days in 0L..30L) {
                val end = start.plusDays(days)
                assertEquals(SearchWarmPolicy.shouldWarm(start, end, today), SearchWarmPolicy.shouldWarmNauSys(start, end, false, today))
                assertEquals(SearchWarmPolicy.shouldWarm(start, end, today), SearchWarmPolicy.shouldWarmAny(start, end, false, today))
            }
        }
    }

    @Test
    fun `MMK is today's rule regardless of the switch`() {
        for (day in DayOfWeek.entries) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (days in 0L..30L) {
                val end = start.plusDays(days)
                assertEquals(SearchWarmPolicy.shouldWarm(start, end, today), SearchWarmPolicy.shouldWarmMmk(start, end, today), "$start +$days")
            }
        }
        // the ranges B3 opens for NauSys stay closed for MMK
        assertFalse(SearchWarmPolicy.shouldWarmMmk(anchor, anchor.plusWeeks(2), today))
        assertFalse(SearchWarmPolicy.shouldWarmMmk(LocalDate.of(2027, 6, 9), LocalDate.of(2027, 6, 16), today))
    }

    @Test
    fun `switch ON - any partner warms exactly when NauSys does (MMK is a subset)`() {
        for (day in DayOfWeek.entries) {
            val start = anchor.with(TemporalAdjusters.nextOrSame(day))
            for (days in 0L..30L) {
                val end = start.plusDays(days)
                assertEquals(SearchWarmPolicy.shouldWarmNauSys(start, end, true, today), SearchWarmPolicy.shouldWarmAny(start, end, true, today))
                if (SearchWarmPolicy.shouldWarmMmk(start, end, today)) assertTrue(SearchWarmPolicy.shouldWarmNauSys(start, end, true, today))
            }
        }
    }

    @Test
    fun `past ranges never warm, switch ON or OFF, either partner`() {
        val pastWednesday = LocalDate.of(2026, 9, 30)
        for (start in listOf(pastWednesday, today.minusDays(1))) {
            for (days in listOf(7L, 10L, 14L)) {
                val end = start.plusDays(days)
                assertFalse(SearchWarmPolicy.shouldWarmNauSys(start, end, true, today), "$start +$days ON")
                assertFalse(SearchWarmPolicy.shouldWarmNauSys(start, end, false, today), "$start +$days OFF")
                assertFalse(SearchWarmPolicy.shouldWarmMmk(start, end, today), "$start +$days MMK")
                assertFalse(SearchWarmPolicy.shouldWarmAny(start, end, true, today), "$start +$days any")
            }
        }
        // starting today is not past
        assertTrue(SearchWarmPolicy.shouldWarmNauSys(today, today.plusDays(14), true, today))
    }
}
