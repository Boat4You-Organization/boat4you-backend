package hr.workspace.boat4you.domains.reservation.mapper

import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.reservation.dto.MyReservationDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDto
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationView
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import org.springframework.stereotype.Component

@Component
class ReservationMappers(
    private val extrasMapper: YachtExtrasMapper,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
) {
    fun toReservationDto(reservation: Reservation): ReservationDto {
        return ReservationDto(
            id = reservation.id!!,
            reservationFlowId = reservation.reservationFlow!!.id!!,
            yachtId = reservation.reservationFlow!!.yacht!!.id!!,
            dateFrom = reservation.dateFrom!!,
            dateTo = reservation.dateTo!!,
            totalPrice = reservation.totalPrice!!,
            paymentPhases = paymentPhasesService.getPaymentPhases(reservation.id!!),
            currency = reservation.currency!!,
            status = reservation.status!!,
            expiresAt = reservation.optionExpiresAt,
            reservationNumber = reservation.reservationNumber,
        )
    }

    fun toMyReservationsResponse(
        reservationView: ReservationView,
        currency: CurrencyEnum,
    ): MyReservationsDto =
        MyReservationsDto(
            reservationId = reservationView.reservationId!!,
            status = reservationView.reservationSysStatus!!,
            createdAt = reservationView.reservationCreatedAt!!,
            yachtId = reservationView.yachtId!!,
            yachtName = reservationView.yachtName!!,
            modelName = reservationView.modelName!!,
            yachtImage = reservationView.yachtMainImage!!,
            dateFrom = reservationView.reservationDateFrom!!,
            dateTo = reservationView.reservationDateTo!!,
            checkin = reservationView.offerCheckin,
            checkout = reservationView.offerCheckout,
            locationFrom = reservationView.locationToName!!,
            locationFromCountryCode = reservationView.locationFromCountry!!,
            locationTo = reservationView.locationToName!!,
            locationToCountryCode = reservationView.locationToCountry!!,
            totalPrice = reservationView.calculatedTotalPrice!!,
            totalPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationView.calculatedTotalPrice,
                    currency,
                ),
            // List price (pre-discount) — only surface it if the offer had an
            // ext_base_price higher than the final total; otherwise leave null
            // so the UI doesn't render a useless strike-through equal to the
            // paid price.
            listPrice =
                reservationView.offerListPrice
                    ?.takeIf { it > reservationView.calculatedTotalPrice },
            listPriceInfo =
                reservationView.offerListPrice
                    ?.takeIf { it > reservationView.calculatedTotalPrice }
                    ?.let { exchangeRateCalculationService.calculatePriceInfo(it, currency) },
            yachtSlug =
                SlugUtils.toSlugWithId(
                    reservationView.manufacturerName,
                    reservationView.modelName,
                    reservationView.yachtName,
                    reservationView.yachtId!!,
                ),
            cancellationRequestAt = reservationView.reservationCancelationRequestAt,
            reservationNumber = reservationView.reservationNumber,
            agencyEmail = reservationView.agencyEmail,
            agencyPhone = reservationView.agencyPhone,
        )

    fun toMyReservationDetailsResponse(
        reservationView: ReservationView,
        reservationExtras: List<ReservationExtra>,
        yacht: Yacht,
        language: LanguageEnum,
        currency: CurrencyEnum,
    ): MyReservationDetailsDto {
        val selectedExtrasDto = reservationExtras.map { extrasMapper.toExtrasPriceDto(it, currency) }

        val description =
            yacht.yachtTranslations
                .filter { it.language?.locale == language.locale && it.type == TranslationType.DESCRIPTION }
                .map { it.value }
                .firstOrNull()

        val highlights =
            yacht.yachtTranslations
                .filter { it.language?.locale == language.locale && it.type == TranslationType.HIGHLIGHTS }
                .map { it.value }
                .firstOrNull()

        return MyReservationDetailsDto(
            reservationId = reservationView.reservationId!!,
            status = reservationView.reservationSysStatus!!,
            createdAt = reservationView.reservationCreatedAt!!,
            yachtId = reservationView.yachtId!!,
            yachtName = reservationView.yachtName!!,
            modelName = reservationView.modelName!!,
            yachtImage = reservationView.yachtMainImage!!,
            dateFrom = reservationView.reservationDateFrom!!,
            dateTo = reservationView.reservationDateTo!!,
            checkin = reservationView.offerCheckin,
            checkout = reservationView.offerCheckout,
            locationFrom = reservationView.locationToName!!,
            locationFromCountryCode = reservationView.locationFromCountry!!,
            locationTo = reservationView.locationToName!!,
            locationToCountryCode = reservationView.locationToCountry!!,
            totalPrice = reservationView.calculatedTotalPrice!!,
            totalPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationView.calculatedTotalPrice!!,
                    currency,
                ),
            paymentPhases = paymentPhasesService.getPaymentPhases(reservationView.reservationId!!),
            yachtSlug =
                SlugUtils.toSlugWithId(
                    reservationView.manufacturerName,
                    reservationView.modelName,
                    reservationView.yachtName,
                    reservationView.yachtId!!,
                ),
            selectedExtras = selectedExtrasDto,
            description = description,
            highlights = highlights,
            yachtImages = yacht.yachtImages?.map { it.toDto() } ?: emptyList(),
            maxPersons = yacht.maxPersons,
            cabins = yacht.cabins,
            wc = yacht.wc,
            berths = yacht.berths,
            enginePower = yacht.enginePower,
            fuelTank = yacht.fuelTank,
            waterTank = yacht.waterTank,
            beam = yacht.beam,
            mainSailType = yacht.mainsailType,
            length = yacht.length,
            yachtMainImage = reservationView.yachtMainImage,
            securityDeposit = yacht.deposit,
            insuredSecurityDeposit = yacht.insuredDeposit,
            depositCurrency = yacht.depositCurrency,
            cancellationRequestAt = reservationView.reservationCancelationRequestAt,
            cancellationRequest = reservationView.reservationCancelationRequest,
            reservationNumber = reservationView.reservationNumber,
            agencyEmail = reservationView.agencyEmail,
            agencyPhone = reservationView.agencyPhone,
            clientPriceEur = reservationView.effectiveClientPrice(),
            clientPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationView.effectiveClientPrice(),
                    currency,
                ),
            clientPricePerDayEur =
                reservationView.offerClientPricePerDay(),
            clientPricePerDayInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationView.offerClientPricePerDay(),
                    currency,
                ),
            numberOfDays = reservationView.numberOfDays().toShort(),
            buildYear = yacht.buildYear,
            beamInfo = MeasurementUnitDto.toDto(yacht.beam, language),
            lengthInfo = MeasurementUnitDto.toDto(yacht.length, language),
            crewNumber = yacht.crewNumber,
            charterType = reservationView.charterType,
            vesselType = yacht.vesselType,
            manufacturerName = reservationView.manufacturerName,
            amenities = yacht.yachtEquipments.distinctBy { it.equipmentId }.map { it.toDto() },
            specialRequest = reservationView.reservationFlowRequest,
            // NOTE: adminNotes intentionally NOT exposed here — this is the
            // customer-facing MyReservationDetailsDto. Only the admin DTO
            // (toDetailsDto below) carries it.
        )
    }

    fun toShortDto(reservationView: ReservationView): ReservationViewDto =
        ReservationViewDto(
            reservationId = reservationView.reservationId!!,
            reservationFlowId = reservationView.reservationFlowId!!,
            reservationStatus = reservationView.reservationStatus!!,
            reservationSysStatus = reservationView.reservationSysStatus!!,
            reservationCreatedAt = reservationView.reservationCreatedAt!!,
            reservationOptionExpiresAt = reservationView.reservationOptionExpiresAt,
            reservationTotalPrice = reservationView.calculatedTotalPrice!!,
            reservationDiscount = reservationView.reservationDiscount,
            reservationExternalId = reservationView.reservationExternalId,
            reservationExternalReservationCode = reservationView.reservationExternalReservationCode,
            reservationNumber = reservationView.reservationNumber,
            reservationUserId = reservationView.reservationUserId,
            endUser = "${reservationView.createdForName} ${reservationView.createdForSurname}",
            createdBy = "${reservationView.createdByName} ${reservationView.createdBySurname}",
            offerCheckin = reservationView.offerCheckin,
            offerCheckout = reservationView.offerCheckout,
            agencySourceExternalSystem = ExternalSystemEnum.fromValue(reservationView.agencySourceExternalSystemId!!),
            yachtId = reservationView.yachtId!!,
            yachtSlug =
                SlugUtils.toSlugWithId(
                    reservationView.manufacturerName,
                    reservationView.modelName,
                    reservationView.yachtName,
                    reservationView.yachtId!!,
                ),
            yachtName = reservationView.yachtName!!,
            modelName = reservationView.modelName,
            manufacturerName = reservationView.manufacturerName,
            locationFromName = reservationView.locationFromName,
            locationFromCountry = reservationView.locationFromCountry,
            locationToName = reservationView.locationToName,
            locationToCountry = reservationView.locationToCountry,
            reservationDateFrom = reservationView.reservationDateFrom!!,
            reservationDateTo = reservationView.reservationDateTo!!,
            agencyId = reservationView.agencyId!!,
            agencyName = reservationView.agencyName!!,
            cancellationRequestAt = reservationView.reservationCancelationRequestAt,
            reservationAgencyPrice = reservationView.reservationAgencyPrice,
            reservationCommission = reservationView.reservationCommission,
            reservationAdminNotes = reservationView.reservationAdminNotes,
        )

    fun toDetailsDto(
        reservationView: ReservationView,
        yacht: Yacht,
        reservationExtras: List<ReservationExtra>,
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): ReservationViewDetailsDto {
        val selectedExtrasDto = reservationExtras.map { extrasMapper.toExtrasPriceDto(it, currency) }

        return ReservationViewDetailsDto(
            reservationId = reservationView.reservationId,
            reservationFlowId = reservationView.reservationFlowId,
            reservationStatus = reservationView.reservationStatus!!,
            reservationSysStatus = reservationView.reservationSysStatus,
            reservationExternalStatus = reservationView.reservationExternalStatus,
            reservationCreatedAt =
                reservationView.reservationCreatedAt!!,
            reservationOptionExpiresAt = reservationView.reservationOptionExpiresAt,
            reservationTotalPrice = reservationView.calculatedTotalPrice,
            reservationPaymentPhases = paymentPhasesService.getPaymentPhases(reservationView.reservationId!!),
            reservationDiscount = reservationView.reservationDiscount,
            reservationClientPrice = reservationView.reservationClientPrice,
            reservationExternalId = reservationView.reservationExternalId,
            reservationExternalReservationCode = reservationView.reservationExternalReservationCode,
            reservationNumber = reservationView.reservationNumber,
            reservationNote = reservationView.reservationNote,
            reservationPaymentNote = reservationView.reservationPaymentNote,
            reservationCrewListUrl = reservationView.reservationCrewListUrl,
            reservationUserId = reservationView.reservationUserId,
            endUser = "${reservationView.createdForName} ${reservationView.createdForSurname}",
            endUserEmail = reservationView.reservationFlowEmail!!,
            endUserPhone = reservationView.reservationFlowPhone,
            endUserRequest = reservationView.reservationFlowRequest,
            createdBy = "${reservationView.createdByName} ${reservationView.createdBySurname}",
            createdByEmail = reservationView.createdByEmail!!,
            checkin = reservationView.offerCheckin,
            checkout = reservationView.offerCheckout,
            agencySourceExternalSystem = ExternalSystemEnum.fromValue(reservationView.agencySourceExternalSystemId!!),
            yachtId = reservationView.yachtId,
            yachtSlug =
                SlugUtils.toSlugWithId(
                    manufacturerName = reservationView.manufacturerName,
                    modelName = reservationView.modelName,
                    yachtName = reservationView.yachtName,
                    yachtId = reservationView.yachtId!!,
                ),
            yachtName = reservationView.yachtName,
            modelName = reservationView.modelName,
            yachtMainImage = reservationView.yachtMainImage,
            manufacturerName = reservationView.manufacturerName,
            locationFromName = reservationView.locationFromName,
            locationFromCountry = reservationView.locationFromCountry,
            locationToName = reservationView.locationToName,
            locationToCountry = reservationView.locationToCountry,
            reservationDateFrom =
                reservationView.reservationDateFrom!!,
            reservationDateTo =
                reservationView.reservationDateTo!!,
            selectedExtras = selectedExtrasDto,
            agencyId = reservationView.agencyId!!,
            agencyName = reservationView.agencyName!!,
            agencyEmail = reservationView.agencyEmail!!,
            agencyPhone = reservationView.agencyPhone,
            cancellationRequestAt = reservationView.reservationCancelationRequestAt,
            cancellationRequest = reservationView.reservationCancelationRequest,
            securityDeposit = yacht.deposit,
            insuredSecurityDeposit = yacht.insuredDeposit,
            depositCurrency = yacht.depositCurrency,
            buildYear = yacht.buildYear,
            beamInfo = MeasurementUnitDto.toDto(yacht.beam, language),
            lengthInfo = MeasurementUnitDto.toDto(yacht.length, language),
            crewNumber = yacht.crewNumber,
            vesselType = yacht.vesselType,
            charterType = reservationView.charterType,
            amenities = yacht.yachtEquipments.distinctBy { it.equipmentId }.map { it.toDto() },
            specialRequest = reservationView.reservationFlowRequest,
            adminNotes = reservationView.reservationAdminNotes,
        )
    }
}
