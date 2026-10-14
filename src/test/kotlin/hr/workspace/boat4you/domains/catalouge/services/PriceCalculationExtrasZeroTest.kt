package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
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
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/** D6 (14.10.2026): a selected unpriced optional extra never changes what the customer pays online. */
class PriceCalculationExtrasZeroTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private var nextId = 1L

    private val yacht = Yacht().apply { id = 18886 }
    private val offer =
        Offer().apply {
            id = 5
            yacht = this@PriceCalculationExtrasZeroTest.yacht
            dateFrom = LocalDate.of(2027, 7, 3)
            dateTo = LocalDate.of(2027, 7, 10)
            clientPrice = BigDecimal("4200.00")
        }

    private fun extra(
        name: String,
        price: String,
        obligatory: Boolean,
        paymentType: ExtraPaymentType?,
        payableInBase: Boolean = false,
    ) = YachtExtra().apply {
        id = nextId++
        this.name = name
        extrasId = id
        this.price = BigDecimal(price)
        unit = ExtrasUnitType.PER_BOOKING
        this.obligatory = obligatory
        this.payableInBase = payableInBase
        this.paymentType = paymentType
    }

    private val transitLog = extra("Transit log", "250", obligatory = true, ExtraPaymentType.WITH_BOOKING)

    private fun calculate(
        rows: List<YachtExtra>,
        selected: Set<String>,
        optionalOnSite: Boolean,
    ): PriceCalcDto {
        val yachtExtras = mock(YachtExtraRepository::class.java) { inv -> if (inv.method.name == "findAllByYacht") rows else null }
        val service =
            PriceCalculationService(
                yachtExtraRepository = yachtExtras,
                yachtExtrasMapper = YachtExtrasMapper(exchangeRates),
                exchangeRateCalculationService = exchangeRates,
                externalBaseRepository = mock(ExternalBaseRepository::class.java),
                nauSysObligatoryExtrasService = mock(NauSysObligatoryExtrasService::class.java),
                realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(optionalOnSite),
            )
        return service.calculatePrice(yacht, offer, null, selected)
    }

    @Test
    fun `a selected 0 EUR ON_SITE extra leaves the total unchanged`() {
        for (on in listOf(false, true)) {
            val skipper = extra("Skipper", "0", obligatory = false, ExtraPaymentType.ON_SITE)
            val without = calculate(listOf(transitLog, skipper), emptySet(), on)
            val with = calculate(listOf(transitLog, skipper), setOf(skipper.extrasKey()), on)

            assertEquals(BigDecimal("4450.00"), without.totalPriceEur)
            assertEquals(without.totalPriceEur, with.totalPriceEur)
            val atBase = with.selectedExtrasAtBase.single { it.name == "Skipper" }
            assertEquals(ExtraPaymentType.ON_SITE, atBase.paymentType)
            assertEquals(0, BigDecimal.ZERO.compareTo(atBase.priceEur))
            assertEquals(listOf("Transit log"), with.selectedExtrasInPrice.map { it.name })
        }
    }

    @Test
    fun `an unpriced optional row without a synced type is labelled by the switch, total unchanged`() {
        val legacy = extra("Damage waiver", "0", obligatory = false, paymentType = null, payableInBase = true)
        val off = calculate(listOf(transitLog, legacy), setOf(legacy.extrasKey()), optionalOnSite = false)
        val on = calculate(listOf(transitLog, legacy), setOf(legacy.extrasKey()), optionalOnSite = true)

        assertEquals(BigDecimal("4450.00"), off.totalPriceEur)
        assertEquals(off.totalPriceEur, on.totalPriceEur)
        assertEquals(ExtraPaymentType.INCLUDED, off.selectedExtrasAtBase.single { it.name == "Damage waiver" }.paymentType)
        assertEquals(ExtraPaymentType.ON_SITE, on.selectedExtrasAtBase.single { it.name == "Damage waiver" }.paymentType)
    }
}
