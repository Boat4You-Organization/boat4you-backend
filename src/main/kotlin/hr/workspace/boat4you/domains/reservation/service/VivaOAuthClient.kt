package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.dto.VivaTokenResponseDto
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

@Component
class VivaOAuthClient(
    @Qualifier("vivaOAuthRestClient") private val vivaOAuthRestClient: RestClient,
    @Value("\${application.viva.oauth-url}")
    private val vivaOAuthUrl: String,
    @Value("\${application.viva.client-id}")
    private val vivaClientId: String,
    @Value("\${application.viva.client-secret}")
    private val vivaClientSecret: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Volatile
    private var cached: CachedToken? = null

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    /**
     * Returns a valid Bearer token, refreshing when expiring soon.
     * Refreshes ~60s early to avoid race conditions.
     */
    fun getAccessToken(): String {
        val now = Instant.now()
        cached?.let { c ->
            if (c.expiresAt.isAfter(now.plusSeconds(30))) return c.token
        }
        synchronized(this) {
            cached?.let { c ->
                if (c.expiresAt.isAfter(Instant.now().plusSeconds(30))) return c.token
            }
            val fresh = fetchNewToken()
            cached = fresh
            return fresh.token
        }
    }

    private fun fetchNewToken(): CachedToken {
        val form: MultiValueMap<String, String> =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "client_credentials")
            }

        val response =
            try {
                vivaOAuthRestClient
                    .post()
                    .uri(vivaOAuthUrl)
                    .headers { h: HttpHeaders ->
                        h.setBasicAuth(vivaClientId, vivaClientSecret)
                        h.contentType = MediaType.APPLICATION_FORM_URLENCODED
                    }.body(form)
                    .retrieve()
                    .body(VivaTokenResponseDto::class.java)
                    ?: error("Empty token response from Viva")
            } catch (e: RestClientResponseException) {
                val detail = e.responseBodyAsString
                val errorMessage = "Viva token request failed: ${e.statusCode.value()} ${e.statusText} body=$detail"
                logger.error(errorMessage, e)
                throw IllegalStateException(errorMessage, e)
            }

        val safety = 60L
        val expiresAt = Instant.now().plusSeconds(response.expiresIn - safety)

        return CachedToken(
            token = response.accessToken,
            expiresAt = expiresAt,
        )
    }
}
