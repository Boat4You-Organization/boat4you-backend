package hr.workspace.boat4you.domains.reservation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

@Configuration
class VivaOAuthRestClientConfig(
    @Value("\${application.viva.oauth-url}")
    private val vivaOAuthUrl: String,
) {
    @Bean
    fun vivaOAuthRestClient(builder: RestClient.Builder): RestClient =
        builder
            .baseUrl(vivaOAuthUrl)
            // Default content-type for this client is form-url-encoded (can override per call if needed)
            .defaultHeader("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build()
}
