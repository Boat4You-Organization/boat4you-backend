package hr.workspace.boat4you.domains.social.connectors

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Small JSON-over-HTTP helper shared by the connectors (java.net.http, 15 s connect, 2 min read, no redirects on
 * uploads). A non-2xx answer becomes a [PublishException] with the network's message: 401 / OAuth errors mark the token
 * invalid, 429 and 5xx are retryable. Bodies are never logged.
 */
open class SocialHttp(
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_S))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) {
    val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    data class Answer(
        val status: Int,
        val body: JsonNode,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonNode = send(builder(url, headers).GET().build()).body

    fun postForm(
        url: String,
        form: Map<String, String?>,
        headers: Map<String, String> = emptyMap(),
    ): JsonNode =
        send(
            builder(url, headers)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form)))
                .build(),
        ).body

    fun postJson(
        url: String,
        body: Any,
        headers: Map<String, String> = emptyMap(),
    ): Answer =
        send(
            builder(url, headers)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
        )

    fun postBytes(
        url: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): Answer = send(builder(url, headers).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build())

    fun put(
        url: String,
        publisher: HttpRequest.BodyPublisher,
        headers: Map<String, String>,
    ): Answer = send(builder(url, headers, Duration.ofMinutes(UPLOAD_MIN)).PUT(publisher).build())

    fun putFile(
        url: String,
        file: Path,
        headers: Map<String, String>,
    ): Answer = put(url, HttpRequest.BodyPublishers.ofFile(file), headers)

    protected open fun send(request: HttpRequest): Answer {
        val res =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw PublishException("${request.uri().host} not reachable: ${e.javaClass.simpleName}", retryable = true)
            }
        val body = res.body().takeIf { it.isNotBlank() }?.let { runCatching { json.readTree(it) }.getOrNull() } ?: MissingNode.getInstance()
        if (res.statusCode() !in 200..299) throw failure(request.uri(), res.statusCode(), body, res.body())
        return Answer(res.statusCode(), body, res.headers().map())
    }

    private fun failure(
        uri: URI,
        status: Int,
        body: JsonNode,
        raw: String,
    ): PublishException {
        val message =
            listOf(
                body.path("error").path("message"),
                body.path("error").path("error_user_msg"),
                body.path("error_description"),
                body.path("message"),
                body.path("error").takeIf { it.isTextual } ?: MissingNode.getInstance(),
            ).firstOrNull { it.isTextual && it.asText().isNotBlank() }?.asText()
                ?: raw.take(RAW_MAX).ifBlank { "HTTP $status" }
        val oauth = body.path("error").path("type").asText("") == "OAuthException" || body.path("error").path("code").asInt(0) == META_TOKEN_CODE
        return PublishException(
            "${uri.host} $status: $message",
            retryable = status == TOO_MANY || status >= SERVER_ERROR,
            tokenInvalid = status == UNAUTHORIZED || (oauth && status in 400..499 && body.path("error").path("code").asInt(0) == META_TOKEN_CODE),
        )
    }

    private fun builder(
        url: String,
        headers: Map<String, String>,
        timeout: Duration = Duration.ofSeconds(READ_S),
    ): HttpRequest.Builder = HttpRequest.newBuilder(URI(url)).timeout(timeout).apply { headers.forEach { (k, v) -> header(k, v) } }

    companion object {
        private const val CONNECT_S = 15L
        private const val READ_S = 120L
        private const val UPLOAD_MIN = 30L
        private const val RAW_MAX = 300
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
        private const val UNAUTHORIZED = 401
        private const val META_TOKEN_CODE = 190

        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        fun formBody(form: Map<String, String?>): String = form.filterValues { it != null }.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value!!)}" }

        fun query(
            base: String,
            params: Map<String, String?>,
        ): String = if (params.isEmpty()) base else "$base?${formBody(params)}"

        fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")
    }
}
