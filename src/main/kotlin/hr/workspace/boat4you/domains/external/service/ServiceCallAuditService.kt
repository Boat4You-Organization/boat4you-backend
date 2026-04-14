package hr.workspace.boat4you.domains.external.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCall
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCallRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ServiceCallAuditService(
    private val serviceCallRepository: ServiceCallRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${application.external.sync.audit.enabled}")
    private val enabled: Boolean,
    @Value("\${application.external.sync.audit.response-body}")
    private val responseBodyAudit: Boolean,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(this.javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun serviceCallAudit(
        route: String,
        result: Result<Any?>,
        responseStatus: String?,
        externalSystem: ExternalSystemEnum,
        request: String? = null,
    ) {
        if (!enabled) {
            return
        }

        val response = result.getOrNull()
        // log all failures, log success only if response body audit is enabled
        val responseString =
            if (result.isFailure && response != null) {
                objectMapper.writeValueAsString(response)
            } else if (response != null && responseBodyAudit) {
                objectMapper.writeValueAsString(response)
            } else {
                null
            }
        saveServiceCall(
            route = route,
            requestBody = request,
            responseBody = responseString,
            responseStatus = responseStatus,
            externalSystem = externalSystem,
            success = result.isSuccess,
        )
    }

    private fun saveServiceCall(
        route: String,
        requestBody: String? = null,
        responseBody: String? = null,
        responseStatus: String? = null,
        externalSystem: ExternalSystemEnum,
        success: Boolean = true,
    ) {
//        log.trace("Saving service call")
        val call = ServiceCall()
        call.route = route
        call.requestBody = requestBody
        call.responseBody = responseBody
        call.responseStatus = responseStatus
        call.externalSystemId = externalSystem.value
        call.success = success
        serviceCallRepository.save(call)
    }
}
