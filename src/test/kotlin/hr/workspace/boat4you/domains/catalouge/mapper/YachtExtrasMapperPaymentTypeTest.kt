package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.dto.InternalCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * D6 (14.10.2026): a booked extra keeps the payment type it was booked with; rows booked before V9_82 (NULL) keep
 * the historical on-the-fly classification. The price calculation's fallback follows the D6 switch.
 */
class YachtExtrasMapperPaymentTypeTest {
    private val mapper = YachtExtrasMapper(mock(ExchangeRateCalculationService::class.java))

    private fun booked(
        name: String,
        price: String,
        obligatory: Boolean,
        payableAtBase: Boolean,
        paymentType: ExtraPaymentType?,
    ) = ReservationExtra().apply {
        id = 1
        this.name = name
        this.price = BigDecimal(price)
        unitPrice = BigDecimal(price)
        this.obligatory = obligatory
        this.payableAtBase = payableAtBase
        unit = ExtrasUnitType.PER_BOOKING
        yachtExtrasKey = name
        this.paymentType = paymentType
    }

    private fun calc(
        price: String,
        obligatory: Boolean,
        paymentType: ExtraPaymentType?,
        payableInBase: Boolean = true,
    ) = InternalCalcDto(
        id = 7,
        name = "Skipper",
        labelCode = null,
        unitPriceEur = BigDecimal(price),
        unit = ExtrasUnitType.PER_BOOKING,
        obligatory = obligatory,
        payableInBase = payableInBase,
        offerPriceEur = null,
        offerUnit = null,
        calcPriceEur = null,
        extrasId = 1,
        externalId = null,
        isStartingPrice = null,
        paymentType = paymentType,
    )

    @Test
    fun `a booked extra with a persisted ON_SITE stays ON_SITE`() {
        val dto = mapper.toExtrasPriceDto(booked("Skipper", "0", obligatory = false, payableAtBase = true, ExtraPaymentType.ON_SITE), null, partnerCopy = true)
        assertEquals(ExtraPaymentType.ON_SITE, dto.paymentType)
        // The persisted value wins over what classify() would say for the same row today.
        val prepaid = booked("Tourist tax", "20", obligatory = true, payableAtBase = false, ExtraPaymentType.WITH_BOOKING)
        assertEquals(ExtraPaymentType.WITH_BOOKING, mapper.toExtrasPriceDto(prepaid, null, partnerCopy = true).paymentType)
    }

    @Test
    fun `a legacy booked extra without a stored type keeps its historical label`() {
        // 0 EUR optional extra booked before V9_82: INCLUDED, exactly as before (history never changes).
        val legacyFree = booked("Skipper", "0", obligatory = false, payableAtBase = true, paymentType = null)
        assertEquals(ExtraPaymentType.INCLUDED, mapper.toExtrasPriceDto(legacyFree, null, partnerCopy = true).paymentType)
        val legacyObligatoryFree = booked("Wifi", "0.00", obligatory = true, payableAtBase = false, paymentType = null)
        assertEquals(ExtraPaymentType.INCLUDED, mapper.toExtrasPriceDto(legacyObligatoryFree, null, partnerCopy = true).paymentType)
        // Priced legacy rows: the same name/flag classification as before.
        val legacySkipper = booked("Skipper", "1540", obligatory = false, payableAtBase = true, paymentType = null)
        assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, mapper.toExtrasPriceDto(legacySkipper, null, partnerCopy = true).paymentType)
        val legacyTax = booked("Tourist tax", "20", obligatory = true, payableAtBase = true, paymentType = null)
        assertEquals(ExtraPaymentType.ON_SITE, mapper.toExtrasPriceDto(legacyTax, null, partnerCopy = true).paymentType)
    }

    @Test
    fun `price calculation fallback - an unpriced optional row without a synced type follows the switch`() {
        assertEquals(ExtraPaymentType.ON_SITE, mapper.toExtrasPriceDto(calc("0", obligatory = false, null), null, optionalOnSite = true).paymentType)
        assertEquals(ExtraPaymentType.INCLUDED, mapper.toExtrasPriceDto(calc("0", obligatory = false, null), null, optionalOnSite = false).paymentType)
        assertEquals(ExtraPaymentType.INCLUDED, mapper.toExtrasPriceDto(calc("0", obligatory = true, null), null, optionalOnSite = true).paymentType)
    }

    @Test
    fun `price calculation - a synced payment type always wins`() {
        val synced = calc("0", obligatory = false, ExtraPaymentType.INCLUDED)
        assertEquals(ExtraPaymentType.INCLUDED, mapper.toExtrasPriceDto(synced, null, optionalOnSite = true).paymentType)
        val priced = calc("1540", obligatory = false, null)
        assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, mapper.toExtrasPriceDto(priced, null, optionalOnSite = true).paymentType)
    }
}
