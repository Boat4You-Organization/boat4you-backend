package hr.workspace.boat4you.domains.external.mmk.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class MmkRestClientConfig(
    @Value("\${application.external.mmk.base-url}")
    private val mmkBaseUrl: String,
    @Value("\${application.external.mmk.bearer}")
    private val mmkBearer: String,
    @Value("\${application.external.mmk.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Long,
    @Value("\${application.external.mmk.read-timeout-ms:60000}")
    private val readTimeoutMs: Long,
    // Incident switch (env MMK_WIRE_LOG=true): per-request wire diagnostics. OFF by default —
    // the always-on variant was 59 % of the cusma3 scheduler journal (~35k lines/day) and the
    // POST body carries the customer's name. Per-agency attribution of availability responses
    // now comes from MmkAvailabilityIntegrationService's INFO line instead.
    @Value("\${application.external.mmk.wire-log:false}")
    private val wireLog: Boolean,
) {
    private val log = LoggerFactory.getLogger(MmkRestClientConfig::class.java)

    @Bean("mmkRestClient")
    fun mmkRestClient(): RestClient {
        // F3-001: bound the time a partner can hold a request thread.
        // Same rationale as NauSys — see NauSysRestClientConfig comment.
        val settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(readTimeoutMs))
        return RestClient
            .builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .requestInterceptor { request, body, execution ->
                // Diagnostic — verify exact wire format we send to MMK so we
                // can compare against a working competitor request when the
                // server returns a generic 4xx like "Yacht not available in
                // period." with no further hints. Authorization header is
                // intentionally NOT logged. Only GET bodies (always empty) are
                // printed; a POST/PUT body (Reservation.clientName = PII) is
                // reduced to its size. Logged at INFO so the env switch alone
                // turns the trace on without raising B4Y_LOG_LEVEL for the whole app.
                if (wireLog) {
                    val bodyStr =
                        when {
                            body.isEmpty() -> "<empty>"
                            request.method == HttpMethod.GET -> String(body, Charsets.UTF_8)
                            else -> "<${body.size} bytes, redacted>"
                        }
                    log.info(
                        "MMK request: {} {} headers={} body={}",
                        request.method,
                        request.uri,
                        request.headers.filterKeys { it.lowercase() != "authorization" },
                        bodyStr,
                    )
                }
                val response = execution.execute(request, body)
                if (wireLog) {
                    log.info("MMK response: status={} headers={}", response.statusCode, response.headers)
                } else if (!response.statusCode.is2xxSuccessful) {
                    // Non-2xx stays visible without the switch — one line, no headers/body.
                    log.warn("MMK {} {} -> {}", request.method, request.uri, response.statusCode)
                }
                response
            }.baseUrl(mmkBaseUrl)
            .defaultHeaders {
                it.setBearerAuth(mmkBearer)
            }.build()
    }
}
