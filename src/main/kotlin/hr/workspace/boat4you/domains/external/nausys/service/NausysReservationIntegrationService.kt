package hr.workspace.boat4you.domains.external.nausys.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.model.ReservationData
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.reservation.enums.QuantityUnit
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.model.ExtraWrapper
import hr.workspace.boat4you.domains.reservation.model.PaymentPlanWrapper
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.openapitools.client.nausys.model.RestClient
import org.openapitools.client.nausys.model.RestYachtReservation
import org.openapitools.client.nausys.model.RestYachtReservationBookingRequest
import org.openapitools.client.nausys.model.RestYachtReservationInfoRequest
import org.openapitools.client.nausys.model.RestYachtReservationOptionRequest
import org.openapitools.client.nausys.model.RestYachtReservationPaymentPlan
import org.openapitools.client.nausys.model.RestYachtReservationRequest
import org.openapitools.client.nausys.model.RestYachtReservationService
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class NausysReservationIntegrationService(
    private val nauSysRetryableClient: NauSysRetryableClient,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val objectMapper: ObjectMapper,
    private val locationQueryingService: LocationQueryingService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun getReservation(nausysReservationId: Long): ReservationResponseWrapper {
        log.info("Fetching Nausys reservation with ID: $nausysReservationId")
        val request =
            RestYachtReservationsRequest(
                credentials = nauSysAuthProvider.auth,
                reservations = listOf(nausysReservationId),
            )
        val reservationResponse = nauSysRetryableClient.getReservation(request)
        return toResponseWrapper(reservationResponse)
    }

    fun createOption(
        reservationData: ReservationData,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        log.info("Creating Nausys reservation for yachtId: ${reservationData.externalYachtId}")
        val nausysInfoRequest =
            RestYachtReservationInfoRequest(
                credentials = nauSysAuthProvider.auth,
                yachtID = reservationData.externalYachtId,
                agencyID = reservationData.externalAgencyId,
                displayCurrency = "DEFAULT",
                periodFrom =
                    NauSysDateWrapper(
                        reservationData.startDate.toLocalDate().format(NauSysDateWrapper.DATE_FORMATTER),
                    ),
                periodTo =
                    NauSysDateWrapper(
                        reservationData.endDate.toLocalDate().format(NauSysDateWrapper.DATE_FORMATTER),
                    ),
                client =
                    RestClient(
                        name = reservationData.name,
                        surname = reservationData.surname,
                    ),
            )
        val infoResponse = nauSysRetryableClient.createInfo(nausysInfoRequest)
        val optionRequest =
            RestYachtReservationOptionRequest(
                credentials = nauSysAuthProvider.auth,
                id = infoResponse.id,
                uuid = infoResponse.uuid,
                fallbackToWaitingOption = true,
            )
        val reservationResponse = nauSysRetryableClient.createOption(optionRequest)

        return toResponseWrapper(reservationResponse, fallbackLocationFrom, fallbackLocationTo)
    }

    fun confirmReservation(
        nausysReservationId: Long,
        nausysReservationUUID: String,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        val bookingRequest =
            RestYachtReservationBookingRequest(
                credentials = nauSysAuthProvider.auth,
                id = nausysReservationId,
                uuid = nausysReservationUUID,
            )
        val reservationResponse = nauSysRetryableClient.confirmReservation(bookingRequest)
        return toResponseWrapper(reservationResponse, fallbackLocationFrom, fallbackLocationTo)
    }

    fun cancelOption(
        nausysReservationId: Long,
        nausysReservationUUID: String,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        val stornoRequest =
            RestYachtReservationRequest(
                credentials = nauSysAuthProvider.auth,
                id = nausysReservationId,
                uuid = nausysReservationUUID,
            )
        val reservationResponse = nauSysRetryableClient.stornoOption(stornoRequest)
        return toResponseWrapper(reservationResponse, fallbackLocationFrom, fallbackLocationTo)
    }

    private fun toResponseWrapper(
        reservationResponse: RestYachtReservation,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        // Orphan external_mapping rows (e.g. after a Location dedup) leave the
        // partner→our-location lookup returning null even though a live Location
        // exists for the yacht. Fall back to the offer's persisted location so
        // booking flow doesn't NPE on the response wrapper.
        val locationFrom =
            locationQueryingService.getLocationByExternalIdAndExternalSystemId(
                reservationResponse.locationFromId!!,
                ExternalSystemEnum.NAUSYS.value.toLong(),
            ) ?: fallbackLocationFrom
                ?: error("No Location for NauSys locationFromId=${reservationResponse.locationFromId} and no fallback supplied")
        val locationTo =
            locationQueryingService.getLocationByExternalIdAndExternalSystemId(
                reservationResponse.locationToId!!,
                ExternalSystemEnum.NAUSYS.value.toLong(),
            ) ?: fallbackLocationTo
                ?: error("No Location for NauSys locationToId=${reservationResponse.locationToId} and no fallback supplied")

        val totalDiscount = reservationResponse.discounts?.sumOf { it.amount!!.toBigDecimal() } ?: BigDecimal.ZERO
        val agencyCommission =
            reservationResponse.effectiveAgencyCommissionAmountWithoutVAT?.toBigDecimal()
                ?: BigDecimal.ZERO

        return ReservationResponseWrapper(
            externalId = reservationResponse.id!!,
            externalCode = reservationResponse.uuid!!,
            dateFrom = reservationResponse.periodFrom!!.value!!,
            dateTo = reservationResponse.periodTo!!.value!!,
            createdAt = reservationResponse.optionMadeAt?.value ?: LocalDateTime.now(),
            expiresAt = reservationResponse.optionTill?.value,
            product = CharterType.fromNausysValue(reservationResponse.bookingType),
            locationFromId = reservationResponse.locationFromId!!,
            locationToId = reservationResponse.locationToId!!,
            locationFrom = locationFrom,
            locationTo = locationTo,
            status = OfferStatus.fromNausysValue(reservationResponse.reservationStatus),
            externalStatus = reservationResponse.reservationStatus.toString(),
            basePrice = reservationResponse.priceListPrice!!.toBigDecimal(), // Nausys `priceListPrice` = catalog/list price (pre-discount)
            discount = totalDiscount,
            commission = agencyCommission,
            // What we owe the charter agency. Nausys docs (v6 §1.10.141) define
            // `agencyPrice` as "Final price expected from agency to pay".
            agencyPrice = reservationResponse.agencyPrice?.toBigDecimal(),
            totalPrice = reservationResponse.agencyClientFinalPrice!!.toBigDecimal(),
            clientPrice = reservationResponse.clientPrice!!.toBigDecimal(),
            deposit = reservationResponse.securityDeposit?.toBigDecimal(),
            currency = reservationResponse.currency!!,
            paymentNote = null,
            bankDetails = null,
            note = reservationResponse.comments?.map { it.note }?.joinToString("\n"),
            extras = mapNausysServicesToExtras(reservationResponse.services),
            paymentPlan = mapNausysPaymentyPlanToPaymentPlan(reservationResponse.paymentPlans),
            responseBody = objectMapper.writeValueAsString(reservationResponse),
            crewListUrl = reservationResponse.crewlistlink,
            yachtId = reservationResponse.yachtId!!,
            calculatedSysStatus = ReservationStatus.fromNausysValue(reservationResponse.reservationStatus),
        )
    }

    private fun mapNausysPaymentyPlanToPaymentPlan(paymentPlan: List<RestYachtReservationPaymentPlan>?): List<PaymentPlanWrapper>? {
        return paymentPlan?.map { payment ->
            PaymentPlanWrapper(
                date = payment.date!!.value!!,
                amount = payment.amount?.toBigDecimal() ?: BigDecimal.ZERO,
            )
        }
    }

    private fun mapNausysServicesToExtras(services: List<RestYachtReservationService>?): List<ExtraWrapper>? {
        return services?.map { service ->
            ExtraWrapper(
                externalId = service.serviceId ?: 0L,
                name = null,
                quantity = service.quantityExtras?.toFloat(),
                unit = QuantityUnit.fromNausysValue(service.priceMeasureId),
                price = service.totalPrice?.toBigDecimal(),
                payableInBase = service.calculationType?.value == "SEPARATE_PAYMENT",
            )
        }
    }
}
