package hr.workspace.boat4you.domains.external.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class IntervalProviderTests {
    @Test
    fun `generate 7 day intervals with start and end date for sat-sat with specifying thu as start day`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.SATURDAY,
                DayOfWeek.SATURDAY,
                7,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 20),
            )

        assertEquals(2, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 6), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 13), intervals[0].end)

        assertEquals(LocalDate.of(2125, 1, 13), intervals[1].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[1].end)
    }

    @Test
    fun `generate 7 day intervals with start and end date for sat-sat with specifying thu as start day and fri as end date`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.SATURDAY,
                DayOfWeek.SATURDAY,
                7,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 26),
            )

        assertEquals(2, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 6), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 13), intervals[0].end)

        assertEquals(LocalDate.of(2125, 1, 13), intervals[1].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[1].end)
    }

    @Test
    fun `generate 7 day intervals when minimal duration is 3 days, with start and end date for sat-sat with specifying thu as start day and fri as end date`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.SATURDAY,
                DayOfWeek.SATURDAY,
                3,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 26),
            )

        assertEquals(2, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 6), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 13), intervals[0].end)

        assertEquals(LocalDate.of(2125, 1, 13), intervals[1].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[1].end)
    }

    @Test
    fun `generate 3 day intervals with start and end date for wen-sat with specifying thu as start day`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.WEDNESDAY,
                DayOfWeek.SATURDAY,
                3,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 20),
            )

        assertEquals(2, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 10), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 13), intervals[0].end)

        assertEquals(LocalDate.of(2125, 1, 17), intervals[1].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[1].end)
    }

    @Test
    fun `generate 10 day intervals with start and end date for wen-sat with specifying thu as start day`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.WEDNESDAY,
                DayOfWeek.SATURDAY,
                10,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 20),
            )

        assertEquals(1, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 10), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[0].end)
    }

    @Test
    fun `generate two 10 day intervals with start and end date for wen-sat with specifying thu as start day`() {
        val intervals =
            IntervalProvider.generateIntervals(
                DayOfWeek.WEDNESDAY,
                DayOfWeek.SATURDAY,
                10,
                LocalDate.of(2125, 1, 4),
                LocalDate.of(2125, 1, 27),
            )

        assertEquals(2, intervals.size)

        assertEquals(LocalDate.of(2125, 1, 10), intervals[0].start)
        assertEquals(LocalDate.of(2125, 1, 20), intervals[0].end)

        assertEquals(LocalDate.of(2125, 1, 17), intervals[1].start)
        assertEquals(LocalDate.of(2125, 1, 27), intervals[1].end)
    }

//    @Test
//    fun `generate 7 day intervals with start and end date for sat-sat with specifying wen as start day`() {
//        val intervals =
//            IntervalProvider.generateIntervals(
//                DayOfWeek.SATURDAY,
//                DayOfWeek.SATURDAY,
//                7,
//                LocalDate.of(1970 , 1, 1),
//                LocalDate.of(2025, 9, 26),
//            )
//
//        assertEquals(3, intervals.size)
//    }
}
