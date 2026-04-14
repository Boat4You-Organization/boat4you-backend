package hr.workspace.boat4you.domains.external.enums

enum class ExternalSystemEnum(
    val value: Int,
) {
    UNKNOWN(0),
    MMK(1),
    NAUSYS(2),
    ;

    companion object {
        fun fromValue(value: Int?): ExternalSystemEnum {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}
