package hr.workspace.boat4you.domains.payment.revolut

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Revolut Merchant API (orders). Bearer secret + `Revolut-Api-Version` are added per request by an interceptor and
 * never logged. A non-2xx answer is logged as ONE line (method, path, status, Revolut error `code`) and rethrown as
 * a Spring `HttpClientErrorException` / `HttpServerErrorException` WITHOUT the response body or headers, so no caller
 * can leak an order body (payer e-mail, card data) into the logs by printing the exception.
 *
 * Retry policy: only [getOrder] is retried (I/O errors and 5xx, 3 attempts); [getOrderOnce] is the same read with ONE
 * attempt for request paths where a customer waits. [createOrder] has no idempotency key on Revolut's side — a retry
 * after a lost response would create a second order — and [cancelOrder] changes state, so neither is ever retried;
 * callers dedupe / reconcile instead.
 */
@Component
class RevolutMerchantClient internal constructor(
    private val restClient: RestClient,
    private val properties: RevolutProperties,
) {
    @Autowired
    constructor(restClientBuilder: RestClient.Builder, properties: RevolutProperties) : this(
        configure(restClientBuilder.requestFactory(timeoutRequestFactory()), properties),
        properties,
    )

    /** `POST /api/orders`. Never retried (see class doc). */
    fun createOrder(request: RevolutCreateOrderRequest): RevolutOrder {
        requireEnabled()
        return restClient
            .post()
            .uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RevolutOrder::class.java)
            ?: throw IllegalStateException("Revolut create order: empty response body")
    }

    /** `GET /api/orders/{id}`; `null` when Revolut does not know the order (404). */
    @Retryable(
        retryFor = [ResourceAccessException::class, HttpServerErrorException::class],
        maxAttempts = GET_MAX_ATTEMPTS,
        backoff = Backoff(delay = GET_BACKOFF_DELAY_MS, multiplier = GET_BACKOFF_MULTIPLIER, random = true),
    )
    fun getOrder(orderId: String): RevolutOrder? = readOrder(orderId)

    /**
     * `GET /api/orders/{id}` with exactly ONE attempt (no [Retryable]): the online-booking checkout and its status
     * endpoint, where a customer waits for the answer (Revolut plan 2A.10 §3). `null` when Revolut does not know the order.
     */
    fun getOrderOnce(orderId: String): RevolutOrder? = readOrder(orderId)

    /** `POST /api/orders/{id}/cancel` (only a `pending` order can be cancelled). Never retried. */
    fun cancelOrder(orderId: String): RevolutOrder? {
        requireEnabled()
        requireOrderId(orderId)
        return restClient
            .post()
            .uri("/api/orders/{id}/cancel", orderId)
            .retrieve()
            .body(RevolutOrder::class.java)
    }

    private fun readOrder(orderId: String): RevolutOrder? {
        requireEnabled()
        requireOrderId(orderId)
        return try {
            restClient
                .get()
                .uri("/api/orders/{id}", orderId)
                .retrieve()
                .body(RevolutOrder::class.java)
        } catch (notFound: HttpClientErrorException.NotFound) {
            null
        }
    }

    private fun requireEnabled() {
        check(properties.enabled) { "Revolut Merchant API is not configured" }
    }

    private fun requireOrderId(orderId: String) {
        require(isValidOrderId(orderId)) { "Malformed Revolut order id" }
    }

    companion object {
        /** Pinned Merchant API version (reference page 8.10.2026); change here after the sandbox run if needed. */
        const val API_VERSION = "2026-08-17"
        const val API_VERSION_HEADER = "Revolut-Api-Version"

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 15L
        private const val GET_MAX_ATTEMPTS = 3
        private const val GET_BACKOFF_DELAY_MS = 500L
        private const val GET_BACKOFF_MULTIPLIER = 2.0
        private const val MAX_ERROR_BODY_BYTES = 8192
        private const val MAX_ERROR_CODE_LENGTH = 64

        private val ORDER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val ERROR_CODE_UNSAFE = Regex("[^A-Za-z0-9_.-]")
        private val log = LoggerFactory.getLogger(RevolutMerchantClient::class.java)
        private val errorReader = ObjectMapper()

        /** Revolut order ids are UUID-like; anything else never reaches a URL path. */
        fun isValidOrderId(orderId: String): Boolean = ORDER_ID.matches(orderId)

        internal fun timeoutRequestFactory() =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            }

        /** Base URL, auth/version headers and the body-free error handling; the request factory is the caller's. */
        internal fun configure(
            builder: RestClient.Builder,
            properties: RevolutProperties,
        ): RestClient =
            builder
                .baseUrl(properties.baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor { request, body, execution ->
                    // Headers are set here, per request, and this interceptor never logs anything.
                    request.headers.setBearerAuth(properties.secretKey)
                    request.headers.set(API_VERSION_HEADER, API_VERSION)
                    execution.execute(request, body)
                }.defaultStatusHandler(HttpStatusCode::isError) { request, response ->
                    val status = response.statusCode
                    val code = errorCode(runCatching { response.body.readNBytes(MAX_ERROR_BODY_BYTES) }.getOrNull())
                    val path = request.uri.path
                    log.warn("Revolut {} {} -> {} code={}", request.method, path, status.value(), code ?: "-")
                    val message = "Revolut ${request.method} $path -> ${status.value()}" + (code?.let { " ($it)" } ?: "")
                    throw if (status.is5xxServerError) {
                        HttpServerErrorException.create(message, status, "", HttpHeaders(), ByteArray(0), null)
                    } else {
                        HttpClientErrorException.create(message, status, "", HttpHeaders(), ByteArray(0), null)
                    }
                }.build()

        /** The `code` of a Revolut error body (string or number), reduced to safe characters; never the message. */
        private fun errorCode(body: ByteArray?): String? {
            if (body == null || body.isEmpty()) return null
            val node = runCatching { errorReader.readTree(body) }.getOrNull()?.get("code") ?: return null
            if (!node.isValueNode || node.isNull) return null
            return node
                .asText()
                .replace(ERROR_CODE_UNSAFE, "")
                .take(MAX_ERROR_CODE_LENGTH)
                .ifEmpty { null }
        }
    }
}
