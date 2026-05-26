package hr.workspace.boat4you.domains.catalouge.dto

data class YachtEquipmentDto(
    val id: Long,
    val name: String?,
    val equipment: EquipmentDto?,
    /** Partner-flagged premium item — frontend renders with yellow row tint. */
    val highlight: Boolean = false,
    /** Per-item count when partner ships > 1 (e.g. 4 for "4 x Electric toilet"). */
    val quantity: java.math.BigDecimal? = null,
    /** Free-text qualifier — "Honda 20hp", "130 L", "at the flybridge", etc. */
    val comment: String? = null,
)
