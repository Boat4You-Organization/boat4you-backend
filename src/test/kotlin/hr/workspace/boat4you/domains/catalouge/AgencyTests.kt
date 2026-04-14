package hr.workspace.boat4you.domains.catalouge

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AgencyTests {
    @Test
    fun `test agency getDiscountMultiplier for discount of 5 perc`() {
        val agency = Agency()
        agency.discount = BigDecimal("5")
        val discount = agency.getDiscountOrZero()

        val price =
            PriceCalculations.calculateClientPrice(
                2720.toBigDecimal(),
                discount,
                applyDiscount = true,
            )

        assertEquals(BigDecimal("2584.00"), price)
    }
}
