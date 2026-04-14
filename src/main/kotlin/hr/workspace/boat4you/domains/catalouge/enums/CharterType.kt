package hr.workspace.boat4you.domains.catalouge.enums

enum class CharterType(
    val value: Int,
) {
    UNKNOWN(0),
    BAREBOAT(1),
    CREWED(2),
    CRUISE(3),
    ALL_INCLUSIVE(4),
    ;

    companion object {
        fun fromNausysValue(value: String?): CharterType {
            return when (value?.lowercase()) {
                "bareboat" -> BAREBOAT
                "crewed" -> CREWED
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: String?): CharterType {
            return when (value?.lowercase()) {
                "bareboat" -> BAREBOAT
                "crewed" -> CREWED
                "cruise" -> CRUISE
                "allinclusive" -> ALL_INCLUSIVE
                else -> UNKNOWN
            }
        }
    }
}
