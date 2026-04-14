package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import org.openapitools.client.mmk.model.Flexibility
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

@Component
class MmkOfferIntegrationUtils(
    private val syncConfigurationProperties: SyncConfigurationProperties,
) {
    fun getFirstDaysOfMonths(endDate: LocalDateTime): List<LocalDateTime> {
        val today = LocalDate.now()
        val result = mutableListOf<LocalDateTime>()

        // Start with the first day of the current month
        var currentMonth = YearMonth.from(today)
        var firstDayOfMonth = currentMonth.atDay(1)

        // Add all first days until we reach the end date
        while (!firstDayOfMonth.isAfter(endDate.toLocalDate())) {
            result.add(firstDayOfMonth.atStartOfDay())
            currentMonth = currentMonth.plusMonths(1)
            firstDayOfMonth = currentMonth.atDay(1)
        }

        return result
    }

    fun getTripDurations(start: Int): List<Int> {
        val adjustedStart =
            if (start < syncConfigurationProperties.minDurationDays) {
                syncConfigurationProperties.minDurationDays
            } else {
                start
            }

        return (adjustedStart..8).toList()
    }

    fun getFlexibility(
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): Flexibility {
        if (dateFrom == null || dateTo == null) {
            return Flexibility._6
        }
        val days = dateTo.toEpochDay() - dateFrom.toEpochDay()
        return when (days) {
            in 0..30 -> Flexibility._1
//            in 7..30 -> Flexibility._5
            else -> Flexibility._6
        }
    }
}
