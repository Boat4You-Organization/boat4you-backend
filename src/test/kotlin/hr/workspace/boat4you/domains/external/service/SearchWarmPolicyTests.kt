package hr.workspace.boat4you.domains.external.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
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
}
