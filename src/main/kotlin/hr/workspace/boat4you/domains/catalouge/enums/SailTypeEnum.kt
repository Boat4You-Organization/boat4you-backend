package hr.workspace.boat4you.domains.catalouge.enums

enum class SailTypeEnum(
    val value: Int,
) {
    UNKNOWN(0),
    CLASSIC_SAIL(1),
    ROLLING_SAIL(2),
    ;

    companion object {
        fun fromNausysValue(value: Int?): SailTypeEnum {
            return when (value) {
                1 -> ROLLING_SAIL // furling/roll (samonavijajuće)
                3 -> ROLLING_SAIL // full batten
                4 -> CLASSIC_SAIL // classic/standard
                112782 -> ROLLING_SAIL // half batten
                492236 -> CLASSIC_SAIL // self tacking jib
                10403978 -> CLASSIC_SAIL // hr flok - en jib
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: String?): SailTypeEnum {
            val sanitizedValue = value?.lowercase()
            return when (sanitizedValue) {
                "full batten" -> ROLLING_SAIL
                "furling" -> ROLLING_SAIL
                "self tacking jib" -> CLASSIC_SAIL
                else -> UNKNOWN
            }
        }
    }
}
