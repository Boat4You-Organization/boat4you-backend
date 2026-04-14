package hr.workspace.boat4you.domains.external.mmk.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class MmkRestClientConfig(
    @Value("\${application.external.mmk.base-url}")
    private val mmkBaseUrl: String,
    @Value("\${application.external.mmk.bearer}")
    private val mmkBearer: String,
) {
    @Bean("mmkRestClient")
    fun mmkRestClient(): RestClient {
        return RestClient
            .builder()
            .requestInterceptor { request, body, execution ->
//                log.info("MMK Request: {}", request.uri)
                execution.execute(request, body)
            }.baseUrl(mmkBaseUrl)
            .defaultHeaders {
                it.setBearerAuth(mmkBearer)
            }.build()
    }
}
