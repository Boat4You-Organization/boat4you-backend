package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtCharterType
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

/** The boat page's charter types (YachtDetailsDto.charterType): a gulet never BAREBOAT, every other boat unchanged. */
class YachtMapperGuletCharterTypeTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private val mapper = YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer()))

    private fun yacht(
        vesselType: VesselType,
        modelName: String,
        vararg types: CharterType,
    ): Yacht =
        Yacht().apply {
            id = 18886
            name = "Sylvia R"
            entryType = EntryType.EXTERNAL
            this.vesselType = vesselType
            model = Model().apply { name = modelName }
            yachtCharterTypes = types.map { YachtCharterType(this, it) }.toMutableSet()
        }

    private fun detailTypes(yacht: Yacht) = mapper.toDetailsDto(yacht, emptyList(), emptyList(), null, LanguageEnum.EN).charterType

    @Test
    fun `a gulet tagged BAREBOAT reads CREWED - by vessel type or by model name`() {
        assertEquals(setOf(CREWED), detailTypes(yacht(VesselType.GULET, "Custom", BAREBOAT)))
        // Gallant 11771: MOTOR_YACHT, model "Gulet"
        assertEquals(setOf(CREWED), detailTypes(yacht(VesselType.MOTOR_YACHT, "Gulet", BAREBOAT, CREWED)))
        assertEquals(setOf(ALL_INCLUSIVE), detailTypes(yacht(VesselType.GULET, "Custom", BAREBOAT, ALL_INCLUSIVE)))
        assertEquals(setOf(CREWED), detailTypes(yacht(VesselType.GULET, "Custom")))
    }

    @Test
    fun `every other boat keeps the partner's charter types`() {
        assertEquals(setOf(BAREBOAT, CREWED), detailTypes(yacht(VesselType.SAILING_YACHT, "Oceanis 46.1", BAREBOAT, CREWED)))
        assertEquals(setOf(BAREBOAT), detailTypes(yacht(VesselType.MOTOR_YACHT, "Princess 62", BAREBOAT)))
        assertEquals(emptySet(), detailTypes(yacht(VesselType.CATAMARAN, "Lagoon 42")))
    }
}
