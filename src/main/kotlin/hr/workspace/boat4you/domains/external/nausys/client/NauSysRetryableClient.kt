package hr.workspace.boat4you.domains.external.nausys.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.exceptions.ExternalCancellationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException
import hr.workspace.boat4you.domains.external.exceptions.ExternalReservationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException
import hr.workspace.boat4you.domains.external.service.ServiceCallAuditService
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.openapitools.client.nausys.model.RestFreeYachtsSearchRequest
import org.openapitools.client.nausys.model.RestFreeYachtsSearchResponse
import org.openapitools.client.nausys.model.RestYachtReservation
import org.openapitools.client.nausys.model.RestYachtReservationBookingRequest
import org.openapitools.client.nausys.model.RestYachtReservationInfoRequest
import org.openapitools.client.nausys.model.RestYachtReservationOptionRequest
import org.openapitools.client.nausys.model.RestYachtReservationRequest
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import kotlin.collections.joinToString

@Component
class NauSysRetryableClient(
    private val nauSysClient: NauSysClient,
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
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun getFreeYachts(request: RestFreeYachtsRequest): RestFreeYachtList {
        val response =
            runCatching {
                nauSysClient.defaultApi.getFreeYachts(request)
            }
        serviceCallAuditService.serviceCallAudit(
            "getFreeYachts",
            response,
            response.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        return response.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun getFreeYachtsSearchForAsync(request: RestFreeYachtsSearchRequest): RestFreeYachtsSearchResponse {
        val response =
            runCatching {
                nauSysClient.defaultApi.freeYachtsSearch(request)
            }
        transactionTemplate.execute<Unit> {
            serviceCallAuditService.serviceCallAudit(
                "freeYachtsSearch",
                response,
                response.getOrNull()?.status,
                ExternalSystemEnum.NAUSYS,
                serializeExcludingCredentials(request),
            )
        }
        return response.getOrThrow()
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun getReservation(request: RestYachtReservationsRequest): RestYachtReservation {
        // there is no API for fetching reservation status by ID, so we need to call endpoint for each status
        val optionsResponse = runCatching { nauSysClient.defaultApi.getAllOptions(request) }
        serviceCallAuditService.serviceCallAudit(
            "getAllOptions",
            optionsResponse,
            optionsResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        val options = optionsResponse.getOrThrow()
        if (options.status == "OK" && !options.reservations.isNullOrEmpty()) {
            return options.reservations!![0]!!
        }

        val stornosResponse = runCatching { nauSysClient.defaultApi.stornos(request) }
        serviceCallAuditService.serviceCallAudit(
            "stornos",
            stornosResponse,
            stornosResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        val stornos = stornosResponse.getOrThrow()
        if (stornos.status == "OK" && !stornos.reservations.isNullOrEmpty()) {
            return stornos.reservations!![0]!!
        }

        val reservationsResponse = runCatching { nauSysClient.defaultApi.getAllReservations(request) }
        serviceCallAuditService.serviceCallAudit(
            "getAllReservations",
            reservationsResponse,
            reservationsResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        val reservations = reservationsResponse.getOrThrow()
        if (reservations.status == "OK" && !reservations.reservations.isNullOrEmpty()) {
            return reservations.reservations!![0]!!
        }

        throw ExternalSystemException(
            "Failed to fetch NauSys reservation with externalId: ${request.reservations?.joinToString { "," }}",
        )
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun createInfo(request: RestYachtReservationInfoRequest): RestYachtReservation {
        val nausysReservationInfo = runCatching { nauSysClient.defaultApi.createInfo(request) }
        serviceCallAuditService.serviceCallAudit(
            "createInfo",
            nausysReservationInfo,
            nausysReservationInfo.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        val infoResponse = nausysReservationInfo.getOrThrow()
        if (infoResponse.id == null || infoResponse.status != "OK") {
            throw ExternalOptionException(
                "Failed to create NauSys info agency: ${request.agencyID} yacht: ${request.yachtID} from ${request.periodFrom?.value} to ${request.periodTo?.value}",
            )
        }
        return infoResponse
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun createOption(request: RestYachtReservationOptionRequest): RestYachtReservation {
        val nausysReservationResponse = runCatching { nauSysClient.defaultApi.createOption(request) }
        serviceCallAuditService.serviceCallAudit(
            "createOption",
            nausysReservationResponse,
            nausysReservationResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        val reservationResponse = nausysReservationResponse.getOrThrow()
        if (reservationResponse.id == null || reservationResponse.status != "OK") {
            throw ExternalOptionException("Failed to create NauSys option externalId: ${request.id}  externalUUID: ${request.uuid}")
        }
        return reservationResponse
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun confirmReservation(request: RestYachtReservationBookingRequest): RestYachtReservation {
        val nausysReservationResponse = runCatching { nauSysClient.defaultApi.createBooking(request) }
        serviceCallAuditService.serviceCallAudit(
            "createBooking",
            nausysReservationResponse,
            nausysReservationResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        val reservationResponse = nausysReservationResponse.getOrThrow()
        if (reservationResponse.id == null || reservationResponse.status != "OK") {
            throw ExternalReservationException("Failed to confirm NauSys reservation externalId: ${request.id}  externalUUID: ${request.uuid}")
        }
        return reservationResponse
    }

    @Retryable(
        value = [Exception::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER),
    )
    fun stornoOption(request: RestYachtReservationRequest): RestYachtReservation {
        val nausysReservationResponse = runCatching { nauSysClient.defaultApi.stornoOption(request) }
        serviceCallAuditService.serviceCallAudit(
            "stornoOption",
            nausysReservationResponse,
            nausysReservationResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            serializeExcludingCredentials(request),
        )
        val reservationResponse = nausysReservationResponse.getOrThrow()
        if (reservationResponse.id == null || reservationResponse.status != "OK") {
            throw ExternalCancellationException("Failed to cancel NauSys reservation externalId: ${request.id}  externalUUID: ${request.uuid}") as Throwable
        }
        return reservationResponse
    }

    fun serializeExcludingCredentials(obj: Any): String {
        val node = objectMapper.valueToTree<ObjectNode>(obj)

        // Remove sensitive fields
        node.remove("username")
        node.remove("password")
        node.remove("credentials")

        return objectMapper.writeValueAsString(node)
    }
}
