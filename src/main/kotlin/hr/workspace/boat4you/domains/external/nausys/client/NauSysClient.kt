package hr.workspace.boat4you.domains.external.nausys.client

import org.openapitools.client.nausys.api.DefaultApi
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class NauSysClient(
    @Qualifier("nauSysRestClient") private val restClient: RestClient,
) {
    val defaultApi = DefaultApi(restClient)
}
