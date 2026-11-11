package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

/**
 * The search sliders of b4y and all six sister sites send minLength / maxLength in metres on every
 * locale. On EN they used to be read as feet, so "up to 20 m" became 6.1 m and returned no boats
 * (11.11.2026). The value must reach the WHERE clause unchanged in every language.
 */
class YachtSearchLengthUnitTest {
    private fun params(
        language: LanguageEnum,
        minLength: BigDecimal?,
        maxLength: BigDecimal?,
    ) = YachtSearchParamObject(
        locationIds = null, charterTypes = null, vesselTypes = null, manufacturers = null, models = null,
        mainSailTypes = null, minBuildYear = null, maxBuildYear = null, minPersons = null, maxPersons = null,
        minCabins = null, maxCabins = null, minBerths = null, maxBerths = null,
        minLength = minLength, maxLength = maxLength,
        minPrice = null, maxPrice = null, startDate = null, endDate = null, minWc = null, maxWc = null,
        minEnginePower = null, maxEnginePower = null, currency = CurrencyEnum.EUR, amenities = null,
        services = null, yachtIds = null, language = language,
    )

    @ParameterizedTest
    @EnumSource(LanguageEnum::class)
    fun `length bounds are metres in every language`(language: LanguageEnum) {
        val p = params(language, BigDecimal("12"), BigDecimal("20"))
        assertEquals(BigDecimal("12"), p.getMinLengthInMeters())
        assertEquals(BigDecimal("20"), p.getMaxLengthInMeters())
    }

    @ParameterizedTest
    @EnumSource(LanguageEnum::class)
    fun `absent length bounds stay absent`(language: LanguageEnum) {
        val p = params(language, null, null)
        assertNull(p.getMinLengthInMeters())
        assertNull(p.getMaxLengthInMeters())
    }
}
