package hr.workspace.boat4you.domains.external.mmk.config

import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(MmkRestClientConfig::class.java)

    @Bean("mmkRestClient")
    fun mmkRestClient(): RestClient {
        return RestClient
            .builder()
            .requestInterceptor { request, body, execution ->
                // Diagnostic — verify exact wire format we send to MMK so we
                // can compare against a working competitor request when the
                // server returns a generic 4xx like "Yacht not available in
                // period." with no further hints. Authorization header is
                // intentionally NOT logged. Body is small (Reservation /
                // params), so logging it inline is acceptable here.
                val bodyStr = if (body.isNotEmpty()) String(body, Charsets.UTF_8) else "<empty>"
                log.info(
                    "MMK request: {} {} headers={} body={}",
                    request.method,
                    request.uri,
                    request.headers.filterKeys { it.lowercase() != "authorization" },
                    bodyStr,
                )
                val response = execution.execute(request, body)
                log.info("MMK response: status={} headers={}", response.statusCode, response.headers)
                response
            }.baseUrl(mmkBaseUrl)
            .defaultHeaders {
                it.setBearerAuth(mmkBearer)
            }.build()
    }
}
