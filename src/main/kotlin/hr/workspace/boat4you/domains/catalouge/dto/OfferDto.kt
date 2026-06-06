package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import java.math.BigDecimal
import java.time.LocalDate

data class OfferDto(
    val id: Long?,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPriceEur: BigDecimal? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val totalPriceEur: BigDecimal? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val totalDiscountEur: BigDecimal? = null,
    val totalDiscountInfo: PriceInfoDto? = null,
    val clientPriceInfo: PriceInfoDto? = null,
    val totalPriceCalcInfo: PriceInfoDto? = null,
    // Customer-facing 4-state (Deploy 4): FREE | OPTION | RESERVATION | SERVICE.
    // Was the lossy 3-state SimpleOfferStatus (FREE/OPTION/UNAVAILABLE); the FE
    // gate needs RESERVATION vs SERVICE distinct (both hard-blocked) and OPTION
    // (inquiry-only) separate. Jackson serializes the enum by NAME.
    val status: ExternalReservationStatus? = null,
    val obligatoryExtrasKeys: Set<String> = emptySet(),
    val extras: Set<YachtExtrasDto> = emptySet(),
    val locationFrom: LocationDto?,
    val locationTo: LocationDto?,
    val checkin: String?,
    val checkout: String?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPricePerDayEur: BigDecimal,
    val clientPricePerDayInfo: PriceInfoDto?,
    val numberOfDays: Short,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val listPriceEur: BigDecimal? = null,
    val listPriceInfo: PriceInfoDto? = null,
)
