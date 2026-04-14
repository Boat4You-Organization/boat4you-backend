package hr.workspace.boat4you.common

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class PriceCalculationsTests {
    @Test
    fun `test client price calculation`() {
        var price = PriceCalculations.calculateClientPrice(100.toBigDecimal(), 10.toBigDecimal(), true)
        assertEquals(0, 90.toBigDecimal().compareTo(price))
        price = PriceCalculations.calculateClientPrice(100.toBigDecimal(), 25.toBigDecimal(), true)
        assertEquals(0, 75.toBigDecimal().compareTo(price))
        price = PriceCalculations.calculateClientPrice(100.toBigDecimal(), 50.toBigDecimal(), true)
        assertEquals(0, 50.toBigDecimal().compareTo(price))

        price = PriceCalculations.calculateClientPrice(100.toBigDecimal(), 10.toBigDecimal(), false)
        assertEquals(0, 100.toBigDecimal().compareTo(price))
        price = PriceCalculations.calculateClientPrice(100.toBigDecimal(), 50.toBigDecimal(), false)
        assertEquals(0, 100.toBigDecimal().compareTo(price))

        price = PriceCalculations.calculateClientPrice(6591.15.toBigDecimal(), 1.toBigDecimal(), true)
        assertEquals(0, 6525.2385.toBigDecimal().compareTo(price))

        price = PriceCalculations.calculateClientPrice(6591.15.toBigDecimal(), 0.toBigDecimal(), true)
        assertEquals(0, 6591.15.toBigDecimal().compareTo(price))
    }

    @Test
    fun `calculate extras price per day`() {
    }

    @Test
    fun `calculate extras price per week`() {
        var price =
            PriceCalculations.calculateExtrasPrice(
                100.toBigDecimal(),
                ExtrasUnitType.PER_WEEK,
                LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 9, 8),
                null,
            )
        assertEquals(0, 100.toBigDecimal().compareTo(price.getOrNull()))

        price =
            PriceCalculations.calculateExtrasPrice(
                100.toBigDecimal(),
                ExtrasUnitType.PER_WEEK,
                LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 9, 9),
                null,
            )
        assertEquals(0, 100.toBigDecimal().compareTo(price.getOrNull()))

        price =
            PriceCalculations.calculateExtrasPrice(
                100.toBigDecimal(),
                ExtrasUnitType.PER_WEEK,
                LocalDate.of(2025, 9, 6),
                LocalDate.of(2025, 9, 20),
                null,
            )
        assertEquals(0, 200.toBigDecimal().compareTo(price.getOrNull()))

        price =
            PriceCalculations.calculateExtrasPrice(
                100.toBigDecimal(),
                ExtrasUnitType.PER_WEEK,
                LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 9, 15),
                null,
            )
        assertEquals(0, 200.toBigDecimal().compareTo(price.getOrNull()))

        price =
            PriceCalculations.calculateExtrasPrice(
                100.toBigDecimal(),
                ExtrasUnitType.PER_WEEK,
                LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 9, 16),
                null,
            )
        assertEquals(0, 200.toBigDecimal().compareTo(price.getOrNull()))
    }
}
