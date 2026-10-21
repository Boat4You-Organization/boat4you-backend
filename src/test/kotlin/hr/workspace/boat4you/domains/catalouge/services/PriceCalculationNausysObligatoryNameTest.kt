package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.external.nausys.service.NauSysObligatoryExtrasService
import hr.workspace.boat4you.domains.external.nausys.service.NausysObligatoryExtra
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * 21.10.2026: the NauSys re-quote names a service through ExtraNameNormalizer ("Early check in (reserved in advance)"),
 * while a row NauSys inserted before that rule keeps the raw partner name ("Early check in RESERVED IN ADVANCE") - the
 * NauSys sync writes a name on insert only. The obligatory item already in the price must not be charged a second time.
 */
class PriceCalculationNausysObligatoryNameTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private var nextId = 1L

    private val yacht =
        Yacht().apply {
            id = 20001
            entryType = EntryType.EXTERNAL
        }
    private val offer =
        Offer().apply {
            id = 7
            yacht = this@PriceCalculationNausysObligatoryNameTest.yacht
            dateFrom = LocalDate.of(2027, 7, 3)
            dateTo = LocalDate.of(2027, 7, 10)
            clientPrice = BigDecimal("4200.00")
        }

    // No catalogue link, so extrasKey() is the stored name (how most partner extras are stored).
    private fun extra(
        name: String,
        price: String,
        obligatory: Boolean,
        paymentType: ExtraPaymentType,
    ) = YachtExtra().apply {
        id = nextId++
        this.name = name
        this.price = BigDecimal(price)
        unit = ExtrasUnitType.PER_BOOKING
        this.obligatory = obligatory
        payableInBase = false
        this.paymentType = paymentType
    }

    private fun calculate(
        rows: List<YachtExtra>,
        selected: Set<String>,
        requote: List<NausysObligatoryExtra>,
    ): PriceCalcDto {
        val yachtExtras = mock(YachtExtraRepository::class.java) { inv -> if (inv.method.name == "findAllByYacht") rows else null }
        val nauSys =
            mock(NauSysObligatoryExtrasService::class.java) { inv ->
                if (inv.method.name == "obligatoryWithSelectedServices") requote else null
            }
        val service =
            PriceCalculationService(
                yachtExtraRepository = yachtExtras,
                yachtExtrasMapper = YachtExtrasMapper(exchangeRates),
                exchangeRateCalculationService = exchangeRates,
                externalBaseRepository = mock(ExternalBaseRepository::class.java),
                nauSysObligatoryExtrasService = nauSys,
                realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(false),
            )
        return service.calculatePrice(yacht, offer, null, selected)
    }

    @Test
    fun `an obligatory row stored under its raw or its normalized name is counted once when the re-quote returns it`() {
        for (stored in listOf("Early check in RESERVED IN ADVANCE", "Early check in (reserved in advance)")) {
            nextId = 1L
            val earlyCheckIn = extra(stored, "100", obligatory = true, ExtraPaymentType.WITH_BOOKING)
            val skipper = extra("Skipper", "1400", obligatory = false, ExtraPaymentType.ADVANCE_TO_OPERATOR)
            val requote =
                listOf(
                    NausysObligatoryExtra(11, "Early check in (reserved in advance)", BigDecimal("100"), "INCLUDED_IN_PRICE"),
                    NausysObligatoryExtra(12, "Damage waiver", BigDecimal("150"), "INCLUDED_IN_PRICE"),
                )

            val price = calculate(listOf(earlyCheckIn, skipper), setOf(skipper.extrasKey()), requote)

            // 4200 + the early check-in from the base calc + the damage waiver the partner made obligatory.
            assertEquals(0, BigDecimal("4450").compareTo(price.totalPriceEur), "$stored: ${price.totalPriceEur}")
            assertEquals(listOf(stored, "Damage waiver"), price.selectedExtrasInPrice.map { it.name }, stored)
        }
    }
}
