package hr.workspace.boat4you.domains.catalouge.enums

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class OfferType(
    val value: Int,
) {
    UNKNOWN(0),
    STANDARD(1),
    OTHER(2),
    ;

    companion object {
        fun getFromDates(
            dateFrom: LocalDate,
            dateTo: LocalDate,
        ): OfferType {
            val areBothSaturdays =
                dateFrom.dayOfWeek == DayOfWeek.SATURDAY &&
                    dateTo.dayOfWeek == DayOfWeek.SATURDAY

            val daysApart = abs(ChronoUnit.DAYS.between(dateFrom, dateTo))

            return if (areBothSaturdays && daysApart == 7L) {
                STANDARD
            } else {
                OTHER
            }
        }
    }
}
