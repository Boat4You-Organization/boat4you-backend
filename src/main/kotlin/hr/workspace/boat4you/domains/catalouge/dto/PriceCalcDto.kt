package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import java.math.BigDecimal
import java.time.LocalDate

data class PriceCalcDto(
    val offerId: Long,
    val yachtId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPricePerDayEur: BigDecimal,
    val clientPricePerDayInfo: PriceInfoDto?,
    val numberOfDays: Short,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPriceEur: BigDecimal,
    val clientPriceInfo: PriceInfoDto?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val totalPriceEur: BigDecimal,
    val totalPriceInfo: PriceInfoDto?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val totalDiscountEur: BigDecimal,
    val totalDiscountInfo: PriceInfoDto?,
    val selectedExtrasAtBase: List<ExtrasPriceDto>,
    val selectedExtrasInPrice: List<ExtrasPriceDto>,
    val inquire: Boolean,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val securityDeposit: BigDecimal?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val insuredSecurityDeposit: BigDecimal?,
    val depositCurrency: String?,
    val multipleExtrasInPriceOptionsAvailable: Boolean,
    val multipleExtrasInBaseOptionsAvailable: Boolean,
    val extrasPriceCalcInquire: Boolean,
)

data class ExtrasPriceDto(
    val id: Long,
    val name: String,
    val labelCode: String?,
    val priceEur: BigDecimal,
    val priceInfo: PriceInfoDto?,
    val obligatory: Boolean,
    val payableInBase: Boolean,
    val unit: ExtrasUnitType,
    val unitPriceEur: BigDecimal,
    val unitPriceInfo: PriceInfoDto?,
    val key: String,
    @field:JsonIgnore
    val extrasId: Long?,
    @field:JsonIgnore
    val externalId: Long?,
    val isStartingPrice: Boolean?,
) {
    fun shouldDisplay(): Boolean {
        return !labelCode.isNullOrBlank() || priceEur > BigDecimal.ZERO && obligatory
    }
}

data class InternalCalcDto(
    val id: Long,
    val name: String,
    val labelCode: String?,
    val unitPriceEur: BigDecimal,
    val unit: ExtrasUnitType,
    val obligatory: Boolean,
    val payableInBase: Boolean,
    var offerPriceEur: BigDecimal?,
    var offerUnit: ExtrasUnitType?,
    var calcPriceEur: BigDecimal?,
    val extrasId: Long?,
    val externalId: Long?,
    val isStartingPrice: Boolean?,
) {
    fun getFinalPrice(): BigDecimal {
        return calcPriceEur ?: offerPriceEur ?: unitPriceEur
    }

    fun getFinalUnit(): ExtrasUnitType {
        return offerUnit ?: unit
    }

    fun extrasKey(): String {
        return extrasId?.toString() ?: name
    }
}
