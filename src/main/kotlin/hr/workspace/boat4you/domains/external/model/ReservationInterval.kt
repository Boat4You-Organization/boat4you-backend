package hr.workspace.boat4you.domains.external.model

import java.time.DayOfWeek

data class ReservationInterval(
    val startDay: DayOfWeek,
    val endDay: DayOfWeek,
    val duration: Int,
)
