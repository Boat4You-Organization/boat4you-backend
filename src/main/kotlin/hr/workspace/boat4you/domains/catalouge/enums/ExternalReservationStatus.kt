package hr.workspace.boat4you.domains.catalouge.enums

import org.openapitools.client.nausys.model.RestYachtReservationOccupancy

enum class ExternalReservationStatus(
    val value: Int,
) {
    UNKNOWN(0),
    OPTION(1),
    RESERVATION(2),
    SERVICE(3),
    FREE(4),
    ;

    companion object {
        fun fromNausysValue(value: RestYachtReservationOccupancy.ReservationType?): ExternalReservationStatus {
            return when (value) {
                RestYachtReservationOccupancy.ReservationType.OPTION -> OPTION
                RestYachtReservationOccupancy.ReservationType.RESERVATION -> RESERVATION
                RestYachtReservationOccupancy.ReservationType.SERVICE -> SERVICE
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: Long?): ExternalReservationStatus {
            return when (value?.toInt()) {
                1 -> RESERVATION
                2 -> OPTION
                3 -> UNKNOWN
                4 -> SERVICE
                5 -> UNKNOWN
                6 -> UNKNOWN
                7 -> FREE
                8 -> UNKNOWN
                9 -> UNKNOWN
                10 -> UNKNOWN
                11 -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}
