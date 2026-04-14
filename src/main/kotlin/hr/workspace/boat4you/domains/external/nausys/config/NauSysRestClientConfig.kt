package hr.workspace.boat4you.domains.external.nausys.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class NauSysRestClientConfig(
    @Value("\${application.external.nausys.base-url}")
    private val nauSysBaseUrl: String,
) {
    @Bean("nauSysRestClient")
    fun nauSysRestClient(): RestClient {
        return RestClient
            .builder()
            .baseUrl(nauSysBaseUrl)
            .build()
    }
}
