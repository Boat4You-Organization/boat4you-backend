package hr.workspace.boat4you.domains.external.nausys.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

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
            .build()
    }
}
