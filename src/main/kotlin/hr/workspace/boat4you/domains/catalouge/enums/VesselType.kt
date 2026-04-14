package hr.workspace.boat4you.domains.catalouge.enums

enum class VesselType(
    val value: Int,
) {
    OTHER(0),
    CATAMARAN(1),
    GULET(2),
    HOUSE_BOAT(3),
    LUXURY_MOTOR_YACHT(4),
    MINI_CRUISER(5),
    MOTORBOAT(6),
    MOTOR_YACHT(7),
    MOTORSAILER(8),
    POWER_CATAMARAN(9),
    SAILING_YACHT(10),
    TRIMARAN(11),
    RUBBER_BOAT(12),
    ;

    companion object {
        fun fromNauSysCategoryId(categoryId: Long?): VesselType {
            return when (categoryId) {
                51L -> CATAMARAN
                102L -> GULET
                43242759L -> HOUSE_BOAT
                4942740L -> CATAMARAN // nausys LUXURY_CATAMARAN
                828326L -> LUXURY_MOTOR_YACHT
                625371L -> MOTORSAILER // nausys LUXURY_SAILING_YACHT
                12798239L -> MINI_CRUISER
                120895L -> MOTORBOAT
                101L -> MOTOR_YACHT
                126977L -> MOTORSAILER
                112727L -> POWER_CATAMARAN
                1L -> SAILING_YACHT
                1505715L -> GULET // nausys WOODEN_YACHT
                else -> OTHER
            }
        }

        fun fromMmkYachtType(yachtType: String?): VesselType {
            return when (yachtType?.lowercase()) {
                "sail boat" -> SAILING_YACHT
                "motor boat" -> MOTORBOAT
                "catamaran" -> CATAMARAN
                "gulet" -> GULET
                "motorsailer" -> MOTORSAILER
                "motoryacht" -> MOTOR_YACHT
                "wooden boat" -> GULET
                "cruiser" -> MINI_CRUISER
                "power catamaran" -> POWER_CATAMARAN
                "trimaran" -> TRIMARAN
                "houseboat" -> HOUSE_BOAT
                "rubber boat" -> RUBBER_BOAT
                "other" -> OTHER // skroz izbaci
                else -> OTHER
            }
        }

        fun shouldSkipVesselType(vesselType: VesselType): Boolean {
            return vesselType == HOUSE_BOAT || vesselType == RUBBER_BOAT || vesselType == OTHER || vesselType == TRIMARAN
        }
    }
}
