package hr.workspace.boat4you.domains.reservation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class VivaApiRestClientConfig(
    @Value("\${application.viva.api-url}")
    private val vivaApiUrl: String,
) {
    @Bean
    fun vivaApiRestClient(builder: RestClient.Builder): RestClient =
        builder
            .baseUrl(vivaApiUrl)
            .build()
}
