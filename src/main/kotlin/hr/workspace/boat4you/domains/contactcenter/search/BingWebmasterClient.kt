package hr.workspace.boat4you.domains.contactcenter.search

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bing Webmaster Tools JSON API, read-only, with the account API key of `seo.bing-api-key` (only on the scheduler node; blank
 * = not configured, nothing is called). The key travels in the query string, so URLs of these calls are never logged.
 * Bing keeps about six months: site traffic per day, query and page rows per week; it has no country or device here.
 */
@Component
class BingWebmasterClient(
    @Value("\${seo.bing-api-key:}") private val apiKey: String,
) : BingSearchApi {
    override val enabled: Boolean get() = apiKey.isNotBlank()

    private val http: RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()

    override fun sites(): Map<String, String> =
        call("GetUserSites", null)
            .filter { it.path("IsVerified").asBoolean() }
            .mapNotNull { n ->
                val url = n.path("Url").asText()
                runCatching { URI(url).host }.getOrNull()?.let(SiteIds::fromHost)?.let { it to url }
            }.toMap()

    override fun rows(
        siteUrl: String,
        grain: SearchGrain,
    ): Map<LocalDate, List<SearchRow>> =
        call(METHODS.getValue(grain), siteUrl)
            .mapNotNull { n -> day(n.path("Date").asText())?.let { it to row(n, grain) } }
            .groupBy({ it.first }, { it.second })

    private fun call(
        method: String,
        siteUrl: String?,
    ): List<JsonNode> {
        val uri =
            UriComponentsBuilder
                .fromUriString("$API/$method")
                .queryParam("apikey", apiKey)
                .apply { if (siteUrl != null) queryParam("siteUrl", siteUrl) }
                .encode()
                .build()
                .toUri()
        val res =
            try {
                http.get().uri(uri).retrieve().body(JsonNode::class.java)
            } catch (e: RestClientException) {
                // Spring's message carries the request URL with the key: rethrow with the status or the error type only.
                val why = (e as? RestClientResponseException)?.let { "HTTP ${it.statusCode.value()}" } ?: e.javaClass.simpleName
                error("Bing $method failed: $why")
            }
        return res?.path("d")?.takeIf { it.isArray }?.toList().orEmpty()
    }

    companion object {
        private const val API = "https://ssl.bing.com/webmaster/api.svc/json"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(90)
        private val METHODS =
            mapOf(SearchGrain.SITE to "GetRankAndTrafficStats", SearchGrain.PAGE to "GetPageStats", SearchGrain.QUERY to "GetQueryStats")
        private val WCF_DATE = Regex("""/Date\((-?\d+)(?:([+-])(\d{2})(\d{2}))?\)/""")

        /** A WCF date such as `/Date(1316156400000-0700)/` as the calendar day of its own offset (here 16.9.2011). */
        fun day(text: String): LocalDate? {
            val g = WCF_DATE.find(text)?.groupValues ?: return null
            val offset =
                if (g[2].isEmpty()) {
                    ZoneOffset.UTC
                } else {
                    val sign = if (g[2] == "-") -1 else 1
                    ZoneOffset.ofHoursMinutes(sign * g[3].toInt(), sign * g[4].toInt())
                }
            return Instant.ofEpochMilli(g[1].toLong()).atOffset(offset).toLocalDate()
        }

        /** One row; in GetPageStats the `Query` field holds the page URL. GetRankAndTrafficStats has no position. */
        fun row(
            node: JsonNode,
            grain: SearchGrain,
        ): SearchRow {
            val impressions = node.path("Impressions").asInt()
            val position = node.path("AvgImpressionPosition").asDouble(-1.0)
            val text = node.path("Query").asText()
            return SearchRow(
                query = if (grain == SearchGrain.QUERY) text.take(SearchText.QUERY_MAX) else "",
                path = if (grain == SearchGrain.PAGE) SearchText.path(text) else "",
                clicks = node.path("Clicks").asInt(),
                impressions = impressions,
                positionSum = if (position > 0) position * impressions else 0.0,
            )
        }
    }
}
