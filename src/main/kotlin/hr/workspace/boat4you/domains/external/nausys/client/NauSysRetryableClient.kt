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
        // F3-005: random=true jitters backoff between `delay` and
        // `delay * multiplier`, breaking lockstep retries when many
        // callers fail on the same partner outage burst.
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER, random = true),
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
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER, random = true),
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
        retryFor = [Exception::class],
        // F3-008: the method makes THREE serial partner calls
        // (getAllOptions, stornos, getAllReservations). Default
        // @Retryable on Exception means a clean "not found" outcome
        // — all three calls returned OK but with no results, so we
        // throw ExternalSystemException at the end — would be retried
        // up to 3 times, fanning 3 calls × 3 attempts = 9 partner
        // hits for a single lookup that will deterministically fail.
        // Excluding ExternalSystemException keeps the retry on
        // transient HTTP / partner 5xx errors while letting the
        // "not found" path fail fast after 3 calls.
        noRetryFor = [hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException::class],
        maxAttempts = DEFAULT_MAX_RETRIES,
        backoff = Backoff(delay = DEFAULT_DELAY, multiplier = DEFAULT_MULTIPLIER, random = true),
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

    // F3-002: NO @Retryable on state-changing partner calls. NauSys
    // `createInfo` allocates a new partner-side row that is assigned a
    // stable `id` in the response — retrying on a transient network
    // error (e.g. socket reset after the server already processed the
    // request) would create a duplicate partner row and leave the
    // first as an orphan we never reference. The bounded read timeout
    // from F3-001 makes the failure mode "fail fast, surface to
    // caller" instead of "wedge thread for minutes".
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

    // F3-002: NO @Retryable. Same rationale as createInfo — every retry
    // is a duplicate partner option (yacht hold) creation. The caller
    // sees only the second response, the first option is orphaned and
    // continues to block the yacht for its hold window.
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

    // F3-002: NO @Retryable. Confirming an option flips a yacht from
    // optioned to firmly booked partner-side — a duplicate confirm on
    // retry triggers a duplicate booking + duplicate customer charge
    // (Stripe is already captured by the time we reach this call,
    // F3-022 chain). Mario-side reconciliation if the network fails
    // here is far cheaper than a silent double-booking.
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

    // F3-002: NO @Retryable. Storno is a destructive state-change; a
    // duplicate cancel on retry would fail with "not found" (already
    // cancelled) but waste partner-side rate budget and our own
    // failure logs become misleading. Better: surface the first
    // failure and let the caller decide whether the storno succeeded
    // (the next get-reservation read will reveal partner state).
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
