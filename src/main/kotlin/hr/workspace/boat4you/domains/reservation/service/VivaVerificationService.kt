package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.dto.VivaVerificationKeyDto
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Service
class VivaVerificationService(
    @Qualifier("vivaWebhookVerificationRestClient")
    private val vivaVerificationRestClient: RestClient,
    @Value("\${application.viva.merchant-id}")
    private val vivaMerchantId: String,
    @Value("\${application.viva.webhook-verification-api-key}")
    private val vivaWebhookVerificationApiKey: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    fun fetchVerificationKey(): VivaVerificationKeyDto {
        return try {
            vivaVerificationRestClient
                .get()
                .uri("/api/messages/config/token")
                .headers { h: HttpHeaders ->
                    h.setBasicAuth(vivaMerchantId, vivaWebhookVerificationApiKey)
                }.retrieve()
                .body(VivaVerificationKeyDto::class.java)
                ?: error("Empty verification response from Viva")
        } catch (e: RestClientResponseException) {
            val body = e.responseBodyAsString
            val errorMessage = "Failed to get Viva webhook verification Key: ${e.statusCode.value()} ${e.statusText} body=$body"
            logger.error(errorMessage, e)
            throw IllegalStateException(errorMessage, e)
        }
    }
}
