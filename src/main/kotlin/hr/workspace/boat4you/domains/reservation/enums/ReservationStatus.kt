package hr.workspace.boat4you.domains.reservation.enums

enum class ReservationStatus(
    val value: Int,
) {
    UNKNOWN(0),
    OPTION(1),
    RESERVATION(2),
    CANCELLED(3),
    OPTION_WAITING(4),
    ;

    companion object {
        fun fromNausysValue(value: String?): ReservationStatus {
            return when (value) {
                "FREE" -> UNKNOWN
                "INFO" -> UNKNOWN
                "OPTION" -> OPTION
                "RESERVATION" -> RESERVATION
                "STORNO" -> CANCELLED
                "UNDER_OPTION" -> OPTION_WAITING
                else -> UNKNOWN
            }
        }

        /**
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
        fun fromMmkValue(value: Int?): ReservationStatus {
            return when (value) {
                1 -> RESERVATION
                2 -> OPTION
                3 -> CANCELLED
                4 -> UNKNOWN
                5 -> CANCELLED
                6 -> UNKNOWN
                7 -> UNKNOWN
                8 -> UNKNOWN
                9 -> OPTION_WAITING
                10 -> UNKNOWN
                11 -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}
