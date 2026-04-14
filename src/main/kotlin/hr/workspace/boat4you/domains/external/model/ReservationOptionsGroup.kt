package hr.workspace.boat4you.domains.external.model

import java.time.LocalDate

data class ReservationOptionsGroup(
    val start: LocalDate,
    val end: LocalDate,
    val minimalDuration: Int,
    val checkinMon: Boolean = false,
    val checkinTue: Boolean = false,
    val checkinWed: Boolean = false,
    val checkinThu: Boolean = false,
    val checkinFri: Boolean = false,
    val checkinSat: Boolean = false,
    val checkinSun: Boolean = false,
    val checkoutMon: Boolean = false,
    val checkoutTue: Boolean = false,
    val checkoutWed: Boolean = false,
    val checkoutThu: Boolean = false,
    val checkoutFri: Boolean = false,
    val checkoutSat: Boolean = false,
    val checkoutSun: Boolean = false,
) {
    fun isCheckInInAnyDay(): Boolean {
        return checkinMon && checkinTue && checkinWed && checkinThu && checkinFri && checkinSat && checkinSun
    }

    fun isCheckOutInAnyDay(): Boolean {
        return checkoutMon && checkoutTue && checkoutWed && checkoutThu && checkoutFri && checkoutSat && checkoutSun
    }

    fun hasNonStandardCheckIn(): Boolean {
        return checkinMon || checkinTue || checkinWed || checkinThu || checkinFri || checkinSun
    }

    fun hasNonStandardCheckOut(): Boolean {
        return checkoutMon || checkoutTue || checkoutWed || checkoutThu || checkoutFri || checkoutSun
    }

    /*
     * Check if the reservation option is a standard reservation
     * A standard reservation is defined as having check-in and check-out available on Saturday
     */
    fun hasStandardReservation(): Boolean {
        return checkinSat && checkoutSat
    }
}
