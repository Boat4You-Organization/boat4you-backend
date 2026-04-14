package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.ReservationOption
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class YachtGroupingProviderTests {
    @Test
    fun `groupYachtsByIdenticalReservationOptions with empty list returns empty map`() {
        val yachts = emptyList<Yacht>()
        val result = YachtGroupingProvider.groupYachtsByReservationOptions(yachts)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `groupYachtsByIdenticalReservationOptions groups yachts with identical reservation options`() {
        // Create mock reservation options
        val identicalOption1 =
            ReservationOption().apply {
                dateFrom = LocalDate.of(2024, 6, 1)
                dateTo = LocalDate.of(2024, 6, 15)
                minimalDuration = 3
                checkinMon = true
                checkinTue = false
                checkinWed = true
                checkinThu = false
                checkinFri = true
                checkinSat = false
                checkinSun = true
                checkoutMon = true
                checkoutTue = false
                checkoutWed = true
                checkoutThu = false
                checkoutFri = true
                checkoutSat = false
                checkoutSun = true
            }

        val identicalOption2 =
            ReservationOption().apply {
                dateFrom = LocalDate.of(2024, 6, 1)
                dateTo = LocalDate.of(2024, 6, 15)
                minimalDuration = 3
                checkinMon = true
                checkinTue = false
                checkinWed = true
                checkinThu = false
                checkinFri = true
                checkinSat = false
                checkinSun = true
                checkoutMon = true
                checkoutTue = false
                checkoutWed = true
                checkoutThu = false
                checkoutFri = true
                checkoutSat = false
                checkoutSun = true
            }

        val differentOption =
            ReservationOption().apply {
                dateFrom = LocalDate.of(2024, 7, 1)
                dateTo = LocalDate.of(2024, 7, 15)
                minimalDuration = 5
                checkinMon = false
                checkinTue = true
                checkinWed = false
                checkinThu = true
                checkinFri = false
                checkinSat = true
                checkinSun = false
                checkoutMon = false
                checkoutTue = true
                checkoutWed = false
                checkoutThu = true
                checkoutFri = false
                checkoutSat = true
                checkoutSun = false
            }

        val yacht1 =
            Yacht().apply {
                reservationOptions = mutableSetOf(identicalOption1)
            }
        val yacht2 =
            Yacht().apply {
                reservationOptions = mutableSetOf(identicalOption2)
            }
        val yacht3 =
            Yacht().apply {
                reservationOptions = mutableSetOf(differentOption)
            }

        val yachts = listOf(yacht1, yacht2, yacht3)

        val result = YachtGroupingProvider.groupYachtsByReservationOptions(yachts)

        // Assertions
        assertEquals(2, result.size) // Two different groups
        assertTrue(result.any { group -> group.value.size == 2 }) // One group with 2 yachts
        assertTrue(result.any { group -> group.value.size == 1 }) // One group with 1 yacht
    }

    @Test
    fun `groupYachtsByIdenticalReservationOptions handles yachts with multiple reservation options`() {
        val option1 =
            ReservationOption().apply {
                dateFrom = LocalDate.of(2024, 6, 1)
                dateTo = LocalDate.of(2024, 6, 15)
                minimalDuration = 3
                checkinMon = true
                checkinTue = false
                checkinWed = true
                checkinThu = false
                checkinFri = true
                checkinSat = false
                checkinSun = true
                checkoutMon = true
                checkoutTue = false
                checkoutWed = true
                checkoutThu = false
                checkoutFri = true
                checkoutSat = false
                checkoutSun = true
            }

        val option2 =
            ReservationOption().apply {
                dateFrom = LocalDate.of(2024, 7, 1)
                dateTo = LocalDate.of(2024, 7, 15)
                minimalDuration = 5
                checkinMon = false
                checkinTue = true
                checkinWed = false
                checkinThu = true
                checkinFri = false
                checkinSat = true
                checkinSun = false
                checkoutMon = false
                checkoutTue = true
                checkoutWed = false
                checkoutThu = true
                checkoutFri = false
                checkoutSat = true
                checkoutSun = false
            }

        val yacht1 =
            Yacht().apply {
                reservationOptions = mutableSetOf(option1, option2)
            }

        val yacht2 =
            Yacht().apply {
                reservationOptions = mutableSetOf(option1)
            }

        val yachts = listOf(yacht1, yacht2)

        val result = YachtGroupingProvider.groupYachtsByReservationOptions(yachts)

        assertTrue(result.size >= 2)
    }
}
