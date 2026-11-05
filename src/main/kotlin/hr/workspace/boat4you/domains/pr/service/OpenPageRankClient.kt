package hr.workspace.boat4you.domains.pr.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Domain authority from Open PageRank (free key, the module's only outside service, optional): `pr.opr-api-key` blank
 * = no call and null. GET https://openpagerank.com/api/v1.0/getPageRank?domains[]=<domain> with header `API-OPR`;
 * the answer is `{"status_code":200,"response":[{"status_code":200,"page_rank_decimal":4.52,"domain":"..."}]}`.
 * Any error, timeout or non-200 (outer or per domain) is null and only logged: a placement is never refused for it.
 * The URL is fixed (only the domain varies, URL-encoded into the query), so this is not an SSRF surface.
 */
@Component
class OpenPageRankClient(
    private val props: PrProperties,
) {
    private val log = LoggerFactory.getLogger(OpenPageRankClient::class.java)
    private val json = ObjectMapper()

    /** Test seam: GET [URI] with the key in API-OPR, answering (status, body). */
    internal var transport: (URI, String) -> Pair<Int, String> = ::get

    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(TIMEOUT).build() }

    fun rank(domain: String): BigDecimal? {
        val key = props.oprApiKey.trim()
        val name = domain.trim()
        if (key.isEmpty() || name.isEmpty()) return null
        return try {
            val uri = URI("$ENDPOINT?domains%5B%5D=${URLEncoder.encode(name, Charsets.UTF_8)}")
            val (status, body) = transport(uri, key)
            if (status != 200) {
                log.info("Open PageRank: HTTP {} for {}", status, name)
                return null
            }
            val entry = json.readTree(body).path("response").path(0)
            val value = entry.path("page_rank_decimal")
            if (entry.path("status_code").asInt() != 200 || !value.isNumber) {
                log.info("Open PageRank: no rank for {} ({})", name, entry.path("error").asText(""))
                return null
            }
            value.decimalValue().setScale(2, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.info("Open PageRank: {} for {}", e.javaClass.simpleName, name)
            null
        }
    }

    private fun get(
        uri: URI,
        key: String,
    ): Pair<Int, String> {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(TIMEOUT)
                .header("API-OPR", key)
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private companion object {
        const val ENDPOINT = "https://openpagerank.com/api/v1.0/getPageRank"
        val TIMEOUT: Duration = Duration.ofSeconds(8)
    }
}
