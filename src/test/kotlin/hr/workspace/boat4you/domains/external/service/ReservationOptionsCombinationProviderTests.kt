package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReservationOptionsCombinationProviderTests {
    @Test
    fun `test generating pairs for tue, sat checkin and sat checkout`() {
        val reservationOption =
            ReservationOptionsGroup(
                start = LocalDate.now(),
                end = LocalDate.now(),
                checkinTue = true,
                checkinSat = true,
                checkoutSat = true,
                minimalDuration = 3,
            )

        val combinations = ReservationOptionsCombinationProvider.generateValidCombinations(reservationOption)
        assertEquals(2, combinations.size)
    }

    @Test
    fun `test with all available options and min 10 days duration`() {
        val reservationOption =
            ReservationOptionsGroup(
                start = LocalDate.now(),
                end = LocalDate.now(),
                checkinMon = true,
                checkinTue = true,
                checkinWed = true,
                checkinThu = true,
                checkinFri = true,
                checkinSat = true,
                checkinSun = true,
                checkoutMon = true,
                checkoutTue = true,
                checkoutWed = true,
                checkoutThu = true,
                checkoutFri = true,
                checkoutSat = true,
                checkoutSun = true,
                minimalDuration = 10,
            )

        val combinations = ReservationOptionsCombinationProvider.generateValidCombinations(reservationOption)
        assertTrue(1 == combinations.size)
    }

    @Test
    fun `test with sat-sat available options and min 7 days duration`() {
        val reservationOption =
            ReservationOptionsGroup(
                start = LocalDate.of(1970, 1, 1),
                end = LocalDate.of(2025, 9, 26),
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
                minimalDuration = 7,
            )

        val combinations = ReservationOptionsCombinationProvider.generateValidCombinations(reservationOption)
        assertTrue(1 == combinations.size)
    }
}
