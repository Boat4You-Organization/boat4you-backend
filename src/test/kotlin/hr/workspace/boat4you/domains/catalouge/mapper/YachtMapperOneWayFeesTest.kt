package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.YachtGalleryFixture
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Mario 16.11.2026: one-way fees are never obligatory, shown under "Optional services" for information only. The boat
 * page gets them apart (oneWayFees, not selectable); `services` never carries them.
 */
class YachtMapperOneWayFeesTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private val mapper = YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer()), YachtGalleryFixture.off())
    private var nextId = 1L

    private fun row(
        name: String,
        price: String,
    ) = YachtExtra().apply {
        id = nextId++
        this.name = name
        this.price = BigDecimal(price)
        unit = ExtrasUnitType.PER_BOOKING
        obligatory = true
        payableInBase = false
    }

    @Test
    fun `one-way fees leave services and come apart as non-obligatory information`() {
        val yacht =
            Yacht().apply {
                id = 18077
                name = "Diamond Si"
                entryType = EntryType.EXTERNAL
                vesselType = VesselType.MOTOR_YACHT
                model = Model().apply { name = "Prestige M48" }
            }
        val rows = listOf(row("Charter pack", "680"), row("One way fee Split-Marina Frapa Rogoznica", "350"))

        val details = mapper.toDetailsDto(yacht, emptyList(), rows, null, LanguageEnum.EN)

        assertEquals(listOf("Charter pack"), details.services.map { it.name })
        assertEquals(listOf("One way fee Split-Marina Frapa Rogoznica" to false), details.oneWayFees.map { it.name to it.obligatory })
    }
}
