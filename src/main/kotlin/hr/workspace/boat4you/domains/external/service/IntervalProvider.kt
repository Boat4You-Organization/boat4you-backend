package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.model.SyncInterval
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object IntervalProvider {
    /**
     * Generates a list of sync intervals based on specified parameters
     *
     * @param startDay The day of the week to start each interval
     * @param endDay The day of the week to end each interval
     * @param durationInDays The number of days between start and end of each interval
     * @param startDate Optional starting date (defaults to today if null or in the past)
     * @param endDate The end date after which no more intervals will be generated
     * @return List of SyncInterval objects
     */
    fun generateIntervals(
        startDay: DayOfWeek,
        endDay: DayOfWeek,
        durationInDays: Int,
        startDate: LocalDate? = null,
        endDate: LocalDate,
    ): List<SyncInterval> {
        // Validate interval
        if (durationInDays < 1) {
            return emptyList()
        }

        // Determine the actual start date
        val effectiveStartDate =
            if (startDate == null || startDate.isBefore(LocalDate.now())) {
                LocalDate.now()
            } else {
                startDate
            }

        // Ensure start date is before or equal to end date
        if (effectiveStartDate >= endDate) {
            return emptyList()
        }

        val intervals = mutableListOf<SyncInterval>()

        // Find the first interval start date
        var currentStart =
            effectiveStartDate.with(
                TemporalAdjusters.nextOrSame(startDay),
            )

        while (currentStart <= endDate) {
            // Calculate end date for the current interval
            val currentEnd =
                if (startDay == endDay) {
                    // If same day, end date is the the same day after the duration
                    currentStart.plusDays(durationInDays.toLong()).with(
                        TemporalAdjusters.nextOrSame(endDay),
                    )
                } else {
                    // If different days, use the next occurrence of end day after minimum duration
                    val nextEndDay =
                        currentStart.plusDays(durationInDays.toLong()).with(
                            TemporalAdjusters.nextOrSame(endDay),
                        )

                    // If endDay comes before startDay in the week, we need to move to next week
                    if (nextEndDay.isBefore(currentStart) || nextEndDay.isEqual(currentStart)) {
                        currentStart.with(TemporalAdjusters.next(endDay))
                    } else {
                        nextEndDay
                    }
                }

            // Add interval if it's within the global date range
            if (currentEnd <= endDate) {
                intervals.add(SyncInterval(currentStart, currentEnd))
            }

            // Move to the start day in the next week
            currentStart = currentStart.plusWeeks(1)
        }

        return intervals
    }

    fun generateMonthIntervals(
        start: LocalDate,
        end: LocalDate,
    ): List<SyncInterval> {
        val intervals = mutableListOf<SyncInterval>()
        var currentStart = start

        while (!currentStart.isAfter(end)) {
            // Calculate the end of the current interval
            // Either the end of the current month or the overall end date, whichever comes first
            val currentMonthEnd = currentStart.withDayOfMonth(currentStart.lengthOfMonth())
            val currentEnd = if (currentMonthEnd.isAfter(end)) end else currentMonthEnd

            intervals.add(SyncInterval(currentStart, currentEnd))

            // Move to the start of the next month
            currentStart = currentEnd.plusDays(1)
        }

        return intervals
    }
}
