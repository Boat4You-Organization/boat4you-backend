package hr.workspace.boat4you.domains.external.mmk.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.exceptions.ExternalCancellationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException
import hr.workspace.boat4you.domains.external.exceptions.ExternalReservationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.model.ReservationData
import hr.workspace.boat4you.domains.reservation.enums.QuantityUnit
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.model.ExtraWrapper
import hr.workspace.boat4you.domains.reservation.model.PaymentPlanWrapper
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.openapitools.client.mmk.model.InvoiceItem
import org.openapitools.client.mmk.model.Payment
import org.openapitools.client.mmk.model.Reservation
import org.openapitools.client.mmk.model.ReservationResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import java.math.BigDecimal

@Service
class MmkReservationIntegrationService(
    private val objectMapper: ObjectMapper,
    private val locationQueryingService: LocationQueryingService,
    private val mmkRetryableClient: MmkRetryableClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun getReservation(mmkReservationId: Long): ReservationResponseWrapper {
        log.info("Fetching MMK reservation with ID: $mmkReservationId")

        return try {
            val reservationResponse = mmkRetryableClient.getReservation(mmkReservationId)

            toResponseWrapper(reservationResponse, null)
        } catch (e: Exception) {
            log.error("Error fetching MMK reservation", e)
            throw ExternalSystemException("Failed to fetch MMK reservation with ID: $mmkReservationId")
        }
    }

    fun createOption(
        reservationData: ReservationData,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        val reservationRequest =
            Reservation(
                dateFrom =
                    MmkDateTimeWrapper(
                        reservationData.startDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                    ),
                dateTo =
                    MmkDateTimeWrapper(
                        reservationData.endDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                    ),
                clientName = reservationData.getFullName(),
                yachtId = reservationData.externalYachtId,
            )
        log.info("Creating MMK reservation for yachtId: ${reservationData.externalYachtId} with request: $reservationRequest")

        return try {
            val reservationResponse = mmkRetryableClient.createOption(reservationRequest)
            toResponseWrapper(reservationResponse, null, fallbackLocationFrom, fallbackLocationTo)
        } catch (e: HttpStatusCodeException) {
            // Surface partner's exact reason in the log so we can debug it
            // (e.g. `"Yacht not available in period."`). The exception message
            // also carries the partner reason — ApiErrorHandler logs it as
            // `cause: ...` for backend audit, but the customer-facing toast
            // is overridden to a generic apology (see ApiErrorCodes).
            val partnerMsg = e.responseBodyAsString.trim().removeSurrounding("\"")
            log.error("Error creating MMK option (yachtId={}, partner response: {})", reservationData.externalYachtId, partnerMsg, e)
            throw ExternalOptionException("MMK: ${partnerMsg.ifBlank { "rejected option" }}")
        } catch (e: Exception) {
            log.error("Error creating MMK option (yachtId={})", reservationData.externalYachtId, e)
            throw ExternalOptionException("Failed to create MMK option for yachtId: ${reservationData.externalYachtId}")
        }
    }

    fun confirmReservation(
        mmkReservationId: Long,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        return try {
            val reservationResponse = mmkRetryableClient.confirmReservation(mmkReservationId)
            val crewListLinkResponse =
                try {
                    val response = mmkRetryableClient.crewListLink(reservationResponse.id)
                    response.link
                } catch (e: Exception) {
                    log.error("Error fetching crew list link for MMK reservation ID: $mmkReservationId", e)
                    null
                }
            toResponseWrapper(reservationResponse, crewListLinkResponse, fallbackLocationFrom, fallbackLocationTo)
        } catch (e: Exception) {
            log.error("Error confirming MMK reservation", e)
            throw ExternalReservationException("Failed to confirm MMK reservation with ID: $mmkReservationId")
        }
    }

    fun cancelOption(
        mmkReservationId: Long,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        return try {
            val reservationResponse = mmkRetryableClient.cancelOption(mmkReservationId)
            toResponseWrapper(reservationResponse, null, fallbackLocationFrom, fallbackLocationTo)
        } catch (e: Exception) {
            log.error("Error cancelling MMK reservation", e)
            throw ExternalCancellationException("Failed to cancel MMK reservation with ID: $mmkReservationId")
        }
    }

    private fun toResponseWrapper(
        reservationResponse: ReservationResponse,
        crewListLink: String?,
        fallbackLocationFrom: Location? = null,
        fallbackLocationTo: Location? = null,
    ): ReservationResponseWrapper {
        // Orphan external_mapping rows (e.g. after a Location dedup) leave the
        // partner→our-location lookup returning null even though a live Location
        // exists for the yacht. Fall back to the offer's persisted location so
        // booking flow doesn't NPE on the response wrapper.
        val locationFrom =
            locationQueryingService.getLocationByExternalIdAndExternalSystemId(
                reservationResponse.baseFromId,
                ExternalSystemEnum.MMK.value.toLong(),
            ) ?: fallbackLocationFrom
                ?: error("No Location for MMK baseFromId=${reservationResponse.baseFromId} and no fallback supplied")
        val locationTo =
            locationQueryingService.getLocationByExternalIdAndExternalSystemId(
                reservationResponse.baseToId,
                ExternalSystemEnum.MMK.value.toLong(),
            ) ?: fallbackLocationTo
                ?: error("No Location for MMK baseToId=${reservationResponse.baseToId} and no fallback supplied")

        return ReservationResponseWrapper(
            externalId = reservationResponse.id,
            externalCode = reservationResponse.reservationCode,
            dateFrom = reservationResponse.dateFrom.value!!,
            dateTo = reservationResponse.dateTo.value!!,
            createdAt = reservationResponse.creationDate.value!!,
            expiresAt = reservationResponse.expirationDate?.value,
            product = CharterType.fromMmkValue(reservationResponse.productName),
            locationFromId = reservationResponse.baseFromId,
            locationToId = reservationResponse.baseToId,
            locationFrom = locationFrom,
            locationTo = locationTo,
            status = OfferStatus.fromMmkValue(reservationResponse.status.toInt()),
            externalStatus = reservationResponse.status.toString(),
            basePrice = reservationResponse.basePrice.toBigDecimal(),
            discount = reservationResponse.discount.toBigDecimal(),
            commission = reservationResponse.commission?.toBigDecimal() ?: BigDecimal.ZERO,
            // What we owe the charter agency. MMK swagger (v2.2.1) defines
            // `finalPrice` as "amount the charter operator receives after
            // commission deduction" — i.e. the agency-side net.
            agencyPrice = reservationResponse.finalPrice.toBigDecimal(),
            // FIX: `totalPrice` (what the client actually owes) was previously
            // mapped to MMK's `finalPrice` (agency-side), so the booking was
            // displaying the smaller agency amount to the customer. Correct
            // source is MMK `clientPrice`.
            totalPrice = reservationResponse.clientPrice.toBigDecimal(),
            clientPrice = reservationResponse.clientPrice.toBigDecimal(),
            deposit = reservationResponse.securityDeposit?.toBigDecimal(),
            currency = reservationResponse.currency,
            paymentNote = reservationResponse.termsOfPayment,
            bankDetails = reservationResponse.bankDetails,
            note = reservationResponse.remarks,
            extras = mapMmkExtrasToExtras(reservationResponse.items),
            paymentPlan = mapMmkPaymentyPlanToPaymentPlan(reservationResponse.paymentPlan),
            responseBody = objectMapper.writeValueAsString(reservationResponse),
            crewListUrl = crewListLink,
            yachtId = reservationResponse.yachtId,
            calculatedSysStatus = ReservationStatus.fromMmkValue(reservationResponse.status.toInt()),
        )
    }

    private fun mapMmkExtrasToExtras(extras: List<InvoiceItem>): List<ExtraWrapper> {
        return extras.map { extra ->
            ExtraWrapper(
                externalId = 0,
                name = extra.name,
                quantity = extra.quantity,
                unit = QuantityUnit.fromMmkValue(extra.unit),
                price = extra.price?.toBigDecimal(),
                payableInBase = extra.payableInBase,
            )
        }
    }

    private fun mapMmkPaymentyPlanToPaymentPlan(paymentPlan: List<Payment>): List<PaymentPlanWrapper> {
        return paymentPlan.map { payment ->
            PaymentPlanWrapper(
                date = payment.date!!.value!!.toLocalDate(),
                amount = payment.amount?.toBigDecimal() ?: BigDecimal.ZERO,
            )
        }
    }
}
