package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.model.ReservationInterval
import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import java.time.DayOfWeek

object ReservationOptionsCombinationProvider {
    fun generateValidCombinations(reservationOption: ReservationOptionsGroup): List<ReservationInterval> {
        // If everything is available, sync only saturday to saturday. Other combinations will be fetched on the fly (search or offer endpoint)
        if (reservationOption.isCheckInInAnyDay() && reservationOption.isCheckOutInAnyDay()) {
            if (isValidCombination(DayOfWeek.SATURDAY, DayOfWeek.SATURDAY, reservationOption.minimalDuration)) {
                return mutableListOf(
                    ReservationInterval(
                        DayOfWeek.SATURDAY,
                        DayOfWeek.SATURDAY,
                        reservationOption.minimalDuration,
                    ),
                )
            }
        }

        // Map boolean flags to DayOfWeek
        val checkinDays =
            mapOf(
                DayOfWeek.MONDAY to reservationOption.checkinMon,
                DayOfWeek.TUESDAY to reservationOption.checkinTue,
                DayOfWeek.WEDNESDAY to reservationOption.checkinWed,
                DayOfWeek.THURSDAY to reservationOption.checkinThu,
                DayOfWeek.FRIDAY to reservationOption.checkinFri,
                DayOfWeek.SATURDAY to reservationOption.checkinSat,
                DayOfWeek.SUNDAY to reservationOption.checkinSun,
            ).filter { it.value == true }

        val checkoutDays =
            mapOf(
                DayOfWeek.MONDAY to reservationOption.checkoutMon,
                DayOfWeek.TUESDAY to reservationOption.checkoutTue,
                DayOfWeek.WEDNESDAY to reservationOption.checkoutWed,
                DayOfWeek.THURSDAY to reservationOption.checkoutThu,
                DayOfWeek.FRIDAY to reservationOption.checkoutFri,
                DayOfWeek.SATURDAY to reservationOption.checkoutSat,
                DayOfWeek.SUNDAY to reservationOption.checkoutSun,
            ).filter { it.value == true }

        // Generate combinations with validation
        val combinations = mutableListOf<ReservationInterval>()

        for (checkin in checkinDays.keys) {
            for (checkout in checkoutDays.keys) {
                // Validate checkout is after check-in
                if (isValidCombination(checkin, checkout, reservationOption.minimalDuration)) {
                    combinations.add(ReservationInterval(checkin, checkout, reservationOption.minimalDuration))
                }
            }
        }

        return combinations
    }

    fun isValidCombination(
        checkinDay: DayOfWeek,
        checkoutDay: DayOfWeek,
        minimalDuration: Int,
    ): Boolean {
        if (minimalDuration <= 7) {
            // Calculate days between check-in and check-out
            val daysBetween = calculateDaysBetween(checkinDay, checkoutDay)

            // Check if checkout is after check-in and meets minimal duration
            // Add 1 to daysBetween to account for the stay duration (inclusive of check-in and check-out days)
            return daysBetween + 1 >= minimalDuration
        } else {
            // If minimal duration is more than a week, we can always find a valid combination
            return true
        }
    }

    fun calculateDaysBetween(
        start: DayOfWeek,
        end: DayOfWeek,
    ): Int {
        // If end day is before or same as start day, we wrap around the week
        return if (end.ordinal <= start.ordinal) {
            (7 - start.ordinal + end.ordinal)
        } else {
            end.ordinal - start.ordinal
        }
    }
}
