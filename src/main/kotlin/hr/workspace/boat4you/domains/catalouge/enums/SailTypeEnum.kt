package hr.workspace.boat4you.domains.catalouge.enums

import hr.workspace.boat4you.domains.catalouge.capacity.SailLabels

/**
 * The mainsail FILTER value (yacht.mainsail_type, also genoa_type). Corrected 6.10.2026 (capacity contract v1, Mario
 * decision 3): battened and classic sails are CLASSIC_SAIL, only furling ones ROLLING_SAIL - before, MMK "Full batten"
 * and NauSys full / half batten were ROLLING_SAIL, so "Rolling mainsail" listed boats whose page says "Full batten".
 * The page shows the partner label itself (yacht.mainsail_label, SailKind); this enum only filters.
 */
enum class SailTypeEnum(
    val value: Int,
) {
    UNKNOWN(0),
    CLASSIC_SAIL(1),
    ROLLING_SAIL(2),
    ;

    companion object {
        /** NauSys sailTypes ids: 1 furling/roll; 3 full batten, 4 classic/standard, 112782 half batten, 492236 self
         *  tacking jib, 10403978 jib; anything else UNKNOWN. */
        fun fromNausysValue(value: Int?): SailTypeEnum =
            when (value) {
                1 -> ROLLING_SAIL
                3, 4, 112782, 492236, 10403978 -> CLASSIC_SAIL
                else -> UNKNOWN
            }

        /** MMK mainsailType / genoaType: furling -> ROLLING_SAIL; full / semi / half batten, classic, standard, (self
         *  tacking) jib -> CLASSIC_SAIL; "None", blank or unknown -> UNKNOWN. */
        fun fromMmkValue(value: String?): SailTypeEnum =
            when (SailLabels.kind(value)) {
                SailKind.FURLING -> ROLLING_SAIL
                null -> UNKNOWN
                else -> CLASSIC_SAIL
            }
    }
}
