package hr.workspace.boat4you.common.services

import java.math.BigDecimal
import java.math.RoundingMode

object UnitCalculations {
    const val FEET_IN_METRE = 3.28084

    fun metersToFeet(meters: Double): Double = meters * FEET_IN_METRE

    fun feetToMeters(feet: Double): Double = feet / FEET_IN_METRE

    fun metersToFeet(meters: BigDecimal): BigDecimal = meters.multiply(BigDecimal(FEET_IN_METRE))

    fun feetToMeters(feet: BigDecimal): BigDecimal = feet.divide(BigDecimal(FEET_IN_METRE), 2, RoundingMode.HALF_UP)
}
