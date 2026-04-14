package hr.workspace.boat4you.domains.catalouge.enums

enum class OfferStatus(
    val value: Int,
) {
    UNKNOWN(0),
    FREE(1),
    OPTION(2),
    OPTION_WAITING(3),
    UNAVAILABLE(4),
    RESERVED(5),
    CANCELLED(6),
    SERVICE(7),
    OPTION_EXPIRED(8),
    INFO(9),
    ;

    companion object {
        fun fromNausysValue(value: String?): OfferStatus {
            return when (value) {
                "FREE" -> FREE
                "INFO" -> INFO
                "OPTION" -> OPTION
                "RESERVATION" -> RESERVED
                "STORNO" -> CANCELLED
                "UNDER_OPTION" -> OPTION_WAITING
                else -> UNKNOWN
            }
        }

        /**
         0 Free: Boat is available for booking in that given period. (update 2026-04-03)
         1 Reservation: resource under reservation, not available for the indicated period.
         2 Option: resource under option, the boat is available for a given period only for an option on waiting (status 9).
         3 Option expired: the resource has an option expired, the boat is available.
         4 Service: resource under maintenance, not available.
         5 Cancelled: reservation canceled for that period, the boat is available.
         6 Owner week: boat not available for booking in that given period.
         7 Offer: An offer has been sent to a client for that given period, and the boat is available.
         8 Custom: status for charter internal use, the boat is available in this period.
         9 Option on waiting: resource has a second option for a specific period, this option becomes active when the first useful option expires.
         10 Regatta: More than one resource is booked at the same time, the resources are not available for the given period.
         11 Sleep Aboard: boat not available for booking in that given period
         */
        fun fromMmkValue(value: Int?): OfferStatus {
            return when (value) {
                0 -> FREE
                1 -> RESERVED
                2 -> OPTION
                3 -> OPTION_EXPIRED
                4 -> SERVICE
                5 -> CANCELLED
                6 -> UNAVAILABLE
                7 -> FREE
                8 -> UNAVAILABLE
                9 -> OPTION_WAITING
                10 -> UNAVAILABLE
                11 -> UNAVAILABLE
                else -> UNKNOWN
            }
        }
    }
}
