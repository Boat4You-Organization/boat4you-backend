package hr.workspace.boat4you.domains.catalouge.enums

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
        /**
         * STANDARD = a regular weekly charter slot: exactly 7 days, which by
         * construction starts and ends on the same weekday — ANY weekday.
         * The old rule additionally required Saturday, which silently demoted
         * every Sunday-to-Sunday (etc.) fleet's weeks to OTHER; since the
         * detail strip only renders STANDARD offers, those boats looked
         * permanently sold out (EBcharter MARILA, Mario 12.7.2026 — backfilled
         * by V9_38).
         */
        fun getFromDates(
            dateFrom: LocalDate,
            dateTo: LocalDate,
        ): OfferType {
            val daysApart = abs(ChronoUnit.DAYS.between(dateFrom, dateTo))

            return if (daysApart == 7L) {
                STANDARD
            } else {
                OTHER
            }
        }
    }
}
