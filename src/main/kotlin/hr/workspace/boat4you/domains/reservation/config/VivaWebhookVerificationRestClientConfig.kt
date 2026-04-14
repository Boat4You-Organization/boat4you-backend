package hr.workspace.boat4you.domains.reservation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class VivaWebhookVerificationRestClientConfig(
    @Value("\${application.viva.webhook-verification-base-url}")
    private val vivaWebhookVerificationUrl: String,
) {
    @Bean
    fun vivaWebhookVerificationRestClient(builder: RestClient.Builder): RestClient =
        builder
            .baseUrl(vivaWebhookVerificationUrl)
            .build()
}
