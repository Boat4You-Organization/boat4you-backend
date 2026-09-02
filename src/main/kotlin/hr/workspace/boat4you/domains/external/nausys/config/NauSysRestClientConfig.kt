package hr.workspace.boat4you.domains.external.nausys.config

import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

/**
 * NauSys throttles CONCURRENT calls per user ("429 Too Many Requests for user
 * rest@..."). This interceptor is the single retry layer for 429: it replays
 * the same request with a jittered backoff (honouring `Retry-After` when the
 * partner sends one) and, once the budget is spent, throws
 * [NauSysRateLimitedException] instead of returning the 429 — so the outer
 * `@Retryable` facades (which exclude that exception) never multiply the
 * attempts and callers get one typed failure they can queue for later.
 * Interceptors see the raw status before RestClient turns 4xx into an
 * exception, and the request body is handed in as bytes so POSTs replay safely.
 */
internal class TooManyRequestsRetryInterceptor(
    private val stats: NauSysRateLimitStats,
    private val backoffMs: LongArray = DEFAULT_BACKOFF_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : ClientHttpRequestInterceptor {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        var attempt = 0
        val path = request.uri.path
        while (true) {
            val response = execution.execute(request, body)
            if (response.statusCode.value() != 429) return response
            stats.tooManyRequests.incrementAndGet()
            if (attempt >= backoffMs.size) {
                response.close()
                stats.exhausted.incrementAndGet()
                log.warn("NauSys 429 on {} — budget exhausted after {} attempts", path, attempt + 1)
                throw NauSysRateLimitedException(path, attempt + 1)
            }
            val retryAfterMs = retryAfterMs(response)
            response.close()
            val wait = max(retryAfterMs, backoffMs[attempt]) + Random.nextLong(0, 1_000)
            // WARN once per chain (first 429) so the flood is countable but not deafening;
            // the intermediate retries go to INFO and the exhaustion above back to WARN.
            if (attempt == 0) {
                log.warn("NauSys 429 on {} — retry 1/{} in {} ms (Retry-After {} ms)", path, backoffMs.size, wait, retryAfterMs)
            } else {
                log.info("NauSys 429 on {} — retry {}/{} in {} ms (Retry-After {} ms)", path, attempt + 1, backoffMs.size, wait, retryAfterMs)
            }
            sleep(wait)
            attempt++
        }
    }

    companion object {
        // Shorter than the pre-gate ladder (2/5/10/20/30 s): with the concurrency gate the
        // self-inflicted collisions are gone, so a 429 now means the OTHER node is busy.
        val DEFAULT_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000)
        const val MAX_RETRY_AFTER_MS = 60_000L

        /** `Retry-After` as delta-seconds or RFC-1123 date; 0 when absent/unparseable; capped at 60 s. */
        fun retryAfterMs(response: ClientHttpResponse): Long {
            val raw = response.headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
            raw.toLongOrNull()?.let { return (it * 1_000).coerceIn(0, MAX_RETRY_AFTER_MS) }
            return runCatching {
                val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(Instant.now(), at).toMillis().coerceIn(0, MAX_RETRY_AFTER_MS)
            }.getOrDefault(0)
        }
    }
}

/**
 * Per-JVM cap on in-flight BULK NauSys calls (offer grid, warm search, yacht
 * catalogue, occupancy) for the single shared credential. Registered AFTER the
 * retry interceptor so the permit is held only while the request is on the
 * wire, never during a backoff sleep. Booking-critical endpoints (createInfo /
 * createOption / createBooking / stornoOption / options / stornos /
 * reservations) are deliberately NOT gated so warms can never starve a
 * booking. Fleet budget: cusma2 (API warms) 2 + cusma3 (scheduler) 1.
 */
internal class NauSysConcurrencyGateInterceptor(
    permits: Int,
    private val waitMs: Long,
    private val stats: NauSysRateLimitStats,
    private val gatedPathFragments: List<String> = DEFAULT_GATED_PATHS,
) : ClientHttpRequestInterceptor {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val permits = permits.coerceAtLeast(1)
    private val semaphore = Semaphore(this.permits, true)

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val path = request.uri.path
        if (gatedPathFragments.none { path.contains(it) }) return execution.execute(request, body)
        if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
            stats.exhausted.incrementAndGet()
            log.warn("NauSys concurrency gate: no free slot within {} ms for {} ({} permits)", waitMs, path, permits)
            throw NauSysRateLimitedException(path, 0)
        }
        try {
            return execution.execute(request, body)
        } finally {
            semaphore.release()
        }
    }

    companion object {
        // Matched with `contains` on the request path: the generated client puts ids in
        // the LAST segment for yachts/{companyId} and occupancy/{companyId}/{year}.
        val DEFAULT_GATED_PATHS =
            listOf(
                "/yachtReservation/v6/freeYachts", // freeYachts + freeYachtsSearch
                "/catalogue/v6/yachts/", // allYachts per charter company
                "/yachtReservation/v6/occupancy", // getOccupancyByYear (+ occupancy2/3)
            )
    }
}

/**
 * Jackson problem handler: an enum string NauSys added since the spec was
 * written (e.g. `calculationType: INCLUDED_IN_PRICE`, Set Sail 2026-08-28)
 * used to kill the WHOLE response for that agency. Map unknown enum strings
 * to null instead and WARN once per (enum, value) per JVM — the signal to
 * extend `nausys_v6.openapi.yaml`. Consumers already treat null as
 * "unknown / advance to operator".
 */
internal class UnknownEnumValueHandler : DeserializationProblemHandler() {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun handleWeirdStringValue(
        ctxt: DeserializationContext,
        targetType: Class<*>,
        valueToConvert: String,
        failureMsg: String,
    ): Any? {
        if (!targetType.isEnum) return NOT_HANDLED
        if (seen.add(targetType.name + "=" + valueToConvert)) {
            log.warn("NauSys sent unknown enum value {} for {} — treating as null", valueToConvert, targetType.name)
        }
        return null
    }

    companion object {
        private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/** The mapper the NauSys RestClient deserializes with — Spring defaults (Kotlin module, JavaTime) + unknown-enum tolerance. */
fun nauSysObjectMapper(): ObjectMapper =
    Jackson2ObjectMapperBuilder.json().build<ObjectMapper>().apply {
        addHandler(UnknownEnumValueHandler())
    }

@Configuration
class NauSysRestClientConfig(
    @Value("\${application.external.nausys.base-url}")
    private val nauSysBaseUrl: String,
    @Value("\${application.external.nausys.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Long,
    @Value("\${application.external.nausys.read-timeout-ms:60000}")
    private val readTimeoutMs: Long,
    @Value("\${application.external.nausys.max-concurrent:2}")
    private val maxConcurrent: Int,
    @Value("\${application.external.nausys.gate-wait-ms:20000}")
    private val gateWaitMs: Long,
    private val rateLimitStats: NauSysRateLimitStats,
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
        val mapper = nauSysObjectMapper()
        return RestClient
            .builder()
            .baseUrl(nauSysBaseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            // First registered = outermost: retry wraps the gate, so a backoff sleep never holds a permit.
            .requestInterceptor(TooManyRequestsRetryInterceptor(rateLimitStats))
            .requestInterceptor(NauSysConcurrencyGateInterceptor(maxConcurrent, gateWaitMs, rateLimitStats))
            .messageConverters { converters ->
                converters.replaceAll { if (it is MappingJackson2HttpMessageConverter) MappingJackson2HttpMessageConverter(mapper) else it }
            }
            .build()
    }
}
