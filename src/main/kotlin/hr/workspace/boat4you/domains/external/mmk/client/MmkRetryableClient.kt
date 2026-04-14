package hr.workspace.boat4you.domains.external.mmk.client

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ServiceCallAuditService
import org.openapitools.client.mmk.model.CrewListLink
import org.openapitools.client.mmk.model.Flexibility
import org.openapitools.client.mmk.model.KindEnum
import org.openapitools.client.mmk.model.Offer
import org.openapitools.client.mmk.model.ProductEnum
import org.openapitools.client.mmk.model.Reservation
import org.openapitools.client.mmk.model.ReservationResponse
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class MmkRetryableClient(
    private val mmkClient: MmkClient,
    private val serviceCallAuditService: ServiceCallAuditService,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val DEFAULT_DELAY = 1000L
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MULTIPLIER = 3.0
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun getOffers(
        dateFrom: hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper,
        dateTo: hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper,
        flexibility: Flexibility? = null,
        companyId: List<Long>? = null,
        country: List<String>? = null,
        productName: ProductEnum? = null,
        baseFromId: List<Long>? = null,
        baseToId: List<Long>? = null,
        sailingAreaId: List<Long>? = null,
        yachtId: List<Long>? = null,
        modelId: List<Long>? = null,
        currency: String? = null,
        tripDuration: List<Int>? = null,
        minCabins: Int? = null,
        maxCabins: Int? = null,
        minBerths: Int? = null,
        maxBerths: Int? = null,
        minHeads: Int? = null,
        maxHeads: Int? = null,
        minLength: Float? = null,
        maxLength: Float? = null,
        showOptions: Boolean? = null,
        passengersOnBoard: Int? = null,
        kind: List<KindEnum>? = null,
        minYearOfBuild: Int? = null,
        maxYearOfBuild: Int? = null,
    ): List<Offer> {
        val response =
            runCatching {
                mmkClient.bookingApi.getOffers(
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    flexibility = flexibility,
                    companyId = companyId,
                    country = country,
                    productName = productName,
                    baseFromId = baseFromId,
                    baseToId = baseToId,
                    sailingAreaId = sailingAreaId,
                    yachtId = yachtId,
                    modelId = modelId,
                    currency = currency,
                    tripDuration = tripDuration,
                    minCabins = minCabins,
                    maxCabins = maxCabins,
                    minBerths = minBerths,
                    maxBerths = maxBerths,
                    minHeads = minHeads,
                    maxHeads = maxHeads,
                    minLength = minLength,
                    maxLength = maxLength,
                    showOptions = showOptions ?: true,
                    passengersOnBoard = passengersOnBoard,
                    kind = kind,
                    minYearOfBuild = minYearOfBuild,
                    maxYearOfBuild = maxYearOfBuild,
                )
            }
        serviceCallAuditService.serviceCallAudit(
            "getOffers",
            response,
            null,
            ExternalSystemEnum.MMK,
            "dateFrom: $dateFrom, dateTo: $dateTo, flexibility: $flexibility, companyId: $companyId, country: $country, productName: $productName, baseFromId: $baseFromId, baseToId: $baseToId, sailingAreaId: $sailingAreaId, yachtId: $yachtId, modelId: $modelId, currency: $currency, tripDuration: $tripDuration, minCabins: $minCabins, maxCabins: $maxCabins, minBerths: $minBerths, maxBerths: $maxBerths, minHeads: $minHeads, maxHeads: $maxHeads, minLength: $minLength, maxLength: $maxLength, showOptions: $showOptions, passengersOnBoard: $passengersOnBoard, kind: $kind, minYearOfBuild: $minYearOfBuild, maxYearOfBuild: $maxYearOfBuild",
        )
        return response.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun getOffersForAsync(
        dateFrom: hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper,
        dateTo: hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper,
        flexibility: Flexibility? = null,
        companyId: List<Long>? = null,
        country: List<String>? = null,
        productName: ProductEnum? = null,
        baseFromId: List<Long>? = null,
        baseToId: List<Long>? = null,
        sailingAreaId: List<Long>? = null,
        yachtId: List<Long>? = null,
        modelId: List<Long>? = null,
        currency: String? = null,
        tripDuration: List<Int>? = null,
        minCabins: Int? = null,
        maxCabins: Int? = null,
        minBerths: Int? = null,
        maxBerths: Int? = null,
        minHeads: Int? = null,
        maxHeads: Int? = null,
        minLength: Float? = null,
        maxLength: Float? = null,
        showOptions: Boolean? = null,
        passengersOnBoard: Int? = null,
        kind: List<KindEnum>? = null,
        minYearOfBuild: Int? = null,
        maxYearOfBuild: Int? = null,
    ): List<Offer> {
        val response =
            runCatching {
                mmkClient.bookingApi.getOffers(
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    flexibility = flexibility,
                    companyId = companyId,
                    country = country,
                    productName = productName,
                    baseFromId = baseFromId,
                    baseToId = baseToId,
                    sailingAreaId = sailingAreaId,
                    yachtId = yachtId,
                    modelId = modelId,
                    currency = currency,
                    tripDuration = tripDuration,
                    minCabins = minCabins,
                    maxCabins = maxCabins,
                    minBerths = minBerths,
                    maxBerths = maxBerths,
                    minHeads = minHeads,
                    maxHeads = maxHeads,
                    minLength = minLength,
                    maxLength = maxLength,
                    showOptions = showOptions ?: true,
                    passengersOnBoard = passengersOnBoard,
                    kind = kind,
                    minYearOfBuild = minYearOfBuild,
                    maxYearOfBuild = maxYearOfBuild,
                )
            }
        transactionTemplate.execute<Unit> {
            serviceCallAuditService.serviceCallAudit(
                "getOffers",
                response,
                null,
                ExternalSystemEnum.MMK,
                "dateFrom: $dateFrom, dateTo: $dateTo, flexibility: $flexibility, companyId: $companyId, country: $country, productName: $productName, baseFromId: $baseFromId, baseToId: $baseToId, sailingAreaId: $sailingAreaId, yachtId: $yachtId, modelId: $modelId, currency: $currency, tripDuration: $tripDuration, minCabins: $minCabins, maxCabins: $maxCabins, minBerths: $minBerths, maxBerths: $maxBerths, minHeads: $minHeads, maxHeads: $maxHeads, minLength: $minLength, maxLength: $maxLength, showOptions: $showOptions, passengersOnBoard: $passengersOnBoard, kind: $kind, minYearOfBuild: $minYearOfBuild, maxYearOfBuild: $maxYearOfBuild",
            )
        }
        return response.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun getReservation(reservationId: Long): ReservationResponse {
        val response = runCatching { mmkClient.bookingApi.getReservation(reservationId) }
        serviceCallAuditService.serviceCallAudit(
            "getReservation",
            response,
            null,
            ExternalSystemEnum.MMK,
            "reservationId: $reservationId",
        )

        return response.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun createOption(request: Reservation): ReservationResponse {
        val reservationResponse = runCatching { mmkClient.bookingApi.createReservation(request) }
        serviceCallAuditService.serviceCallAudit(
            "createReservation",
            reservationResponse,
            null,
            ExternalSystemEnum.MMK,
            objectMapper.writeValueAsString(request),
        )
        return reservationResponse.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun confirmReservation(reservationId: Long): ReservationResponse {
        val reservationResponse = runCatching { mmkClient.bookingApi.confirmReservation(reservationId) }
        serviceCallAuditService.serviceCallAudit(
            "confirmReservation",
            reservationResponse,
            null,
            ExternalSystemEnum.MMK,
            "reservationId: $reservationId",
        )
        return reservationResponse.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun crewListLink(reservationId: Long): CrewListLink {
        val crewListLinkResponse =
            runCatching { mmkClient.bookingApi.crewListLink(reservationId) }
        serviceCallAuditService.serviceCallAudit(
            "crewListLink",
            crewListLinkResponse,
            null,
            ExternalSystemEnum.MMK,
            "reservationId: $reservationId",
        )
        return crewListLinkResponse.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff =
            Backoff(
                delay = DEFAULT_DELAY,
                multiplier = DEFAULT_MULTIPLIER,
            ),
    )
    fun cancelOption(reservationId: Long): ReservationResponse {
        val reservationResponse = runCatching { mmkClient.bookingApi.cancelReservation(reservationId) }
        serviceCallAuditService.serviceCallAudit(
            "cancelReservation",
            reservationResponse,
            null,
            ExternalSystemEnum.MMK,
            "reservationId: $reservationId",
        )
        return reservationResponse.getOrThrow()
    }
}
