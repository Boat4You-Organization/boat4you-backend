package hr.workspace.boat4you.domains.payment.mollie

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
 * Mollie Payments API v2. The API key is added per request by an interceptor and never logged. A non-2xx answer is
 * logged as ONE line (method, path, status, Mollie `title`) and rethrown as a Spring `HttpClientErrorException` /
 * `HttpServerErrorException` WITHOUT the response body, so no caller can leak a payment body into the logs.
 *
 * Retry policy: only [getPayment] is retried (I/O errors and 5xx, 3 attempts); [getPaymentOnce] is the same read with
 * ONE attempt for request paths where a customer waits. [createPayment] sends an `Idempotency-Key` (our own attempt
 * token), so a repeated create returns the same Mollie payment instead of a second one; it is still not retried here.
 */
@Component
class MollieClient internal constructor(
    private val restClient: RestClient,
    private val properties: MollieProperties,
) {
    @Autowired
    constructor(restClientBuilder: RestClient.Builder, properties: MollieProperties) : this(
        configure(restClientBuilder.requestFactory(timeoutRequestFactory()), properties),
        properties,
    )

    /** `POST /v2/payments` with an idempotency key (our attempt token). */
    fun createPayment(
        request: MollieCreatePaymentRequest,
        idempotencyKey: String,
    ): MolliePayment {
        requireEnabled()
        return restClient
            .post()
            .uri("/v2/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .body(request)
            .retrieve()
            .body(MolliePayment::class.java)
            ?: throw IllegalStateException("Mollie create payment: empty response body")
    }

    /** `GET /v2/payments/{id}`; `null` when Mollie does not know the payment (404). */
    @Retryable(
        retryFor = [ResourceAccessException::class, HttpServerErrorException::class],
        maxAttempts = GET_MAX_ATTEMPTS,
        backoff = Backoff(delay = GET_BACKOFF_DELAY_MS, multiplier = GET_BACKOFF_MULTIPLIER, random = true),
    )
    fun getPayment(paymentId: String): MolliePayment? = readPayment(paymentId)

    /** `GET /v2/payments/{id}` with exactly ONE attempt (no [Retryable]): request paths where a customer waits. */
    fun getPaymentOnce(paymentId: String): MolliePayment? = readPayment(paymentId)

    /**
     * `DELETE /v2/payments/{id}` — cancels an open payment when Mollie allows it (`isCancelable`; cards usually are
     * not, they simply expire). Never retried; a refusal is the caller's to ignore.
     */
    fun cancelPayment(paymentId: String): MolliePayment? {
        requireEnabled()
        requirePaymentId(paymentId)
        return restClient
            .delete()
            .uri("/v2/payments/{id}", paymentId)
            .retrieve()
            .body(MolliePayment::class.java)
    }

    /**
     * `GET /v2/payments/{id}?embed=refunds,chargebacks` with ONE attempt: the admin "Details" view (an admin waits).
     * `null` when Mollie does not know the payment.
     */
    fun getPaymentWithMoneyBack(paymentId: String): MolliePayment? = readPayment(paymentId, embedMoneyBack = true)

    private fun readPayment(
        paymentId: String,
        embedMoneyBack: Boolean = false,
    ): MolliePayment? {
        requireEnabled()
        requirePaymentId(paymentId)
        return try {
            restClient
                .get()
                .uri(if (embedMoneyBack) "/v2/payments/{id}?embed=refunds,chargebacks" else "/v2/payments/{id}", paymentId)
                .retrieve()
                .body(MolliePayment::class.java)
        } catch (notFound: HttpClientErrorException.NotFound) {
            null
        }
    }

    private fun requireEnabled() {
        check(properties.enabled) { "Mollie is not configured" }
    }

    private fun requirePaymentId(paymentId: String) {
        require(isValidPaymentId(paymentId)) { "Malformed Mollie payment id" }
    }

    companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 15L
        private const val GET_MAX_ATTEMPTS = 3
        private const val GET_BACKOFF_DELAY_MS = 500L
        private const val GET_BACKOFF_MULTIPLIER = 2.0
        private const val MAX_ERROR_BODY_BYTES = 8192
        private const val MAX_ERROR_TITLE_LENGTH = 64

        /** Mollie payment ids: `tr_` + alphanumerics. Anything else never reaches a URL path. */
        private val PAYMENT_ID = Regex("^tr_[A-Za-z0-9]{1,61}$")
        private val ERROR_TITLE_UNSAFE = Regex("[^A-Za-z0-9 _.-]")
        private val log = LoggerFactory.getLogger(MollieClient::class.java)
        private val errorReader = ObjectMapper()

        fun isValidPaymentId(paymentId: String?): Boolean = paymentId != null && PAYMENT_ID.matches(paymentId)

        internal fun timeoutRequestFactory() =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            }

        /** Base URL, auth header and the body-free error handling; the request factory is the caller's. */
        internal fun configure(
            builder: RestClient.Builder,
            properties: MollieProperties,
        ): RestClient =
            builder
                .baseUrl(properties.baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor { request, body, execution ->
                    // Set here, per request; this interceptor never logs anything.
                    request.headers.setBearerAuth(properties.apiKey)
                    execution.execute(request, body)
                }.defaultStatusHandler(HttpStatusCode::isError) { request, response ->
                    val status = response.statusCode
                    val title = errorTitle(runCatching { response.body.readNBytes(MAX_ERROR_BODY_BYTES) }.getOrNull())
                    val path = request.uri.path
                    log.warn("Mollie {} {} -> {} {}", request.method, path, status.value(), title ?: "-")
                    val message = "Mollie ${request.method} $path -> ${status.value()}" + (title?.let { " ($it)" } ?: "")
                    throw if (status.is5xxServerError) {
                        HttpServerErrorException.create(message, status, "", HttpHeaders(), ByteArray(0), null)
                    } else {
                        HttpClientErrorException.create(message, status, "", HttpHeaders(), ByteArray(0), null)
                    }
                }.build()

        /** The `title` of a Mollie error body (e.g. "Unprocessable Entity"), reduced to safe characters; never `detail`. */
        private fun errorTitle(body: ByteArray?): String? {
            if (body == null || body.isEmpty()) return null
            val node = runCatching { errorReader.readTree(body) }.getOrNull()?.get("title") ?: return null
            if (!node.isTextual) return null
            return node.asText().replace(ERROR_TITLE_UNSAFE, "").take(MAX_ERROR_TITLE_LENGTH).ifEmpty { null }
        }
    }
}
