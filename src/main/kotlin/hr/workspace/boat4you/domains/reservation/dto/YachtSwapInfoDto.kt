package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.reservation.jpa.YachtSwapAction
import java.time.Instant

/**
 * Exposes the latest yacht-swap audit entry for a reservation. Customer
 * endpoint filters to unacknowledged swaps only so the UI can show a dismissible
 * banner; admin endpoint returns the latest regardless of acknowledgement state.
 */
data class YachtSwapInfoDto(
    val detectedAt: Instant,
    val previousYachtId: Long,
    val previousYachtName: String?,
    val newYachtId: Long?,
    val newYachtName: String?,
    val action: YachtSwapAction,
    val acknowledged: Boolean,
    val notes: String?,
)
