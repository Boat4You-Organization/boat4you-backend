package hr.workspace.boat4you.domains.external.nausys.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import java.time.Duration
import kotlin.random.Random

/**
 * NauSys throttles concurrent calls per user ("429 Too Many Requests for user
 * rest@..."). The async offer/availability syncs fan out per agency, so a
 * burst of 429s used to abort whole agency batches every night. Retry the
 * same request with a short jittered backoff instead — interceptors see the
 * raw status before RestClient turns 4xx into an exception, and the request
 * body is handed in as bytes so POSTs replay safely.
 */
private class TooManyRequestsRetryInterceptor : ClientHttpRequestInterceptor {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val backoffMs = longArrayOf(2_000, 5_000, 10_000)

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        var attempt = 0
        while (true) {
            val response = execution.execute(request, body)
            if (response.statusCode.value() != 429 || attempt >= backoffMs.size) return response
            response.close()
            val wait = backoffMs[attempt] + Random.nextLong(0, 1_000)
            log.warn("NauSys 429 on {} — retry {}/{} in {} ms", request.uri.path, attempt + 1, backoffMs.size, wait)
            Thread.sleep(wait)
            attempt++
        }
    }
}

@Configuration
class NauSysRestClientConfig(
    @Value("\${application.external.nausys.base-url}")
    private val nauSysBaseUrl: String,
    @Value("\${application.external.nausys.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Long,
    @Value("\${application.external.nausys.read-timeout-ms:60000}")
    private val readTimeoutMs: Long,
) {
    @Bean("nauSysRestClient")
    fun nauSysRestClient(): RestClient {
        // F3-001: bound the time a partner can hold a request thread.
        // Without these, NauSys connect / read hangs propagate straight
        // into VM2 request-handling threads (and into `@Scheduled` sync
        // jobs sharing the same pool — F4-001 family). The detected
        // factory uses whatever HTTP client is on the classpath
        // (HttpComponents preferred, then Jetty, then JDK / Simple).
        val settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(readTimeoutMs))
        return RestClient
            .builder()
            .baseUrl(nauSysBaseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .requestInterceptor(TooManyRequestsRetryInterceptor())
            .build()
    }
}
