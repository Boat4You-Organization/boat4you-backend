package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import java.time.LocalDate

object YachtGroupingProvider {
    fun groupYachtsByReservationOptions(yachts: List<Yacht>): Map<ReservationOptionsGroup, List<Yacht>> {
        return yachts
            .flatMap { yacht ->
                yacht.reservationOptions.map { option ->
                    val minDuration =
                        if (option.minimalDuration!!.toInt() > 7) {
                            option.minimalDuration!!.toInt()
                        } else {
                            7
                        }
                    val adjustedMinDate =
                        if (option.dateFrom!!.isBefore(LocalDate.now())) {
                            LocalDate.now()
                        } else {
                            option.dateFrom!!
                        }
                    ReservationOptionsGroup(
                        start = adjustedMinDate,
                        end = option.dateTo!!,
                        minimalDuration = minDuration,
                        checkinMon = false,
                        checkinTue = false,
                        checkinWed = false,
                        checkinThu = false,
                        checkinFri = false,
                        checkinSat = true,
                        checkinSun = false,
                        checkoutMon = false,
                        checkoutTue = false,
                        checkoutWed = false,
                        checkoutThu = false,
                        checkoutFri = false,
                        checkoutSat = true,
                        checkoutSun = false,
                    ) to yacht
                }
            }.groupBy({ it.first }, { it.second })
    }

    @Deprecated(
        "Use the new method instead",
        ReplaceWith("YachtGroupingProvider.groupYachtsByReservationOptions(yachts)"),
    )
    fun groupYachtsByReservationOptions2(yachts: List<Yacht>): Map<ReservationOptionsGroup, List<Yacht>> {
        return yachts
            .flatMap { yacht ->
                yacht.reservationOptions.map { option ->
                    ReservationOptionsGroup(
                        start = option.dateFrom!!,
                        end = option.dateTo!!,
                        minimalDuration = option.minimalDuration!!.toInt(),
                        checkinMon = option.checkinMon!!,
                        checkinTue = option.checkinTue!!,
                        checkinWed = option.checkinWed!!,
                        checkinThu = option.checkinThu!!,
                        checkinFri = option.checkinFri!!,
                        checkinSat = option.checkinSat!!,
                        checkinSun = option.checkinSun!!,
                        checkoutMon = option.checkoutMon!!,
                        checkoutTue = option.checkoutTue!!,
                        checkoutWed = option.checkoutWed!!,
                        checkoutThu = option.checkoutThu!!,
                        checkoutFri = option.checkoutFri!!,
                        checkoutSat = option.checkoutSat!!,
                        checkoutSun = option.checkoutSun!!,
                    ) to yacht
                }
            }.groupBy({ it.first }, { it.second })
    }
}
