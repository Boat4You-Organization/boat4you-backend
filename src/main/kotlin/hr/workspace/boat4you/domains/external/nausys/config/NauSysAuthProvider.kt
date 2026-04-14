package hr.workspace.boat4you.domains.external.nausys.config

import org.openapitools.client.nausys.model.RestAuthentication
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class NauSysAuthProvider(
    @Value("\${application.external.nausys.username}") val nauSysUsername: String? = null,
    @Value("\${application.external.nausys.password}") val nauSysPassword: String? = null,
) {
    val auth: RestAuthentication by lazy {
        RestAuthentication(nauSysUsername!!, nauSysPassword!!)
    }
}
