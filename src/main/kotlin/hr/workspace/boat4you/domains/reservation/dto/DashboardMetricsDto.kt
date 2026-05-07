package hr.workspace.boat4you.domains.reservation.dto

import java.math.BigDecimal

/**
 * Aggregates for the admin dashboard. All time-bounded fields are computed
 * server-side using `reservation_view.reservation_created_at` (broker booking
 * time) so the FE shows the same numbers regardless of client clock skew.
 *
 * Cancelled reservations are included in counts (they are real bookings the
 * broker made) but excluded from revenue (no commission earned on them).
 */
data class DashboardMetricsDto(
    /** Bookings created this week (Mon 00:00 → next Mon 00:00, local time). */
    val bookingsThisWeek: Long,
    /** Bookings created this calendar month. */
    val bookingsThisMonth: Long,
    /** All reservations whose sys_status is RESERVATION (confirmed). All-time. */
    val confirmedReservations: Long,
    /** Sum of `reservation_commission` for the current calendar year, excluding CANCELLED. */
    val revenueYearToDate: BigDecimal,
    /**
     * Daily counts for the rolling 7-day window ending today (inclusive).
     * Index 0 = 6 days ago, index 6 = today. Days with no bookings are 0,
     * not omitted — FE renders a bar per index.
     */
    val weeklyChart: List<DailyCount>,
)

data class DailyCount(
    /** ISO date `yyyy-MM-dd`. */
    val day: String,
    val count: Long,
)
