package hr.workspace.boat4you.domains.contactcenter.search

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Date

/**
 * Google Search Console Search Analytics, read-only, as the service account whose JSON key is at `seo.google-key-file`
 * (only on the scheduler node; blank = not configured, nothing is called). The account is an owner of the seven URL-prefix
 * properties `https://www.<host>/`. No SDK: a signed JWT is exchanged for an access token, then plain JSON calls.
 */
@Component
class SearchConsoleClient(
    @Value("\${seo.google-key-file:}") private val keyFile: String,
    private val mapper: ObjectMapper,
) : GoogleSearchApi {
    override val enabled: Boolean get() = keyFile.isNotBlank()

    private val http: RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()

    private class Key(
        val email: String,
        val tokenUri: String,
        val privateKey: PrivateKey,
    )

    private val key: Key by lazy {
        val json = mapper.readTree(File(keyFile))
        val der = Base64.getMimeDecoder().decode(json.path("private_key").asText().replace(PEM_ARMOUR, ""))
        Key(
            email = json.path("client_email").asText(),
            tokenUri = json.path("token_uri").asText(TOKEN_URI),
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)),
        )
    }

    @Volatile private var token: Pair<String, Instant>? = null

    override fun rows(
        siteId: String,
        day: LocalDate,
        grain: SearchGrain,
    ): List<SearchRow> {
        val uri = URI("$API/sites/${URLEncoder.encode(siteUrl(siteId), Charsets.UTF_8)}/searchAnalytics/query")
        val dimensions = DIMENSIONS.getValue(grain)
        val out = mutableListOf<SearchRow>()
        var start = 0
        do {
            val body =
                mapOf(
                    "startDate" to day.toString(),
                    "endDate" to day.toString(),
                    "dimensions" to dimensions,
                    "type" to "web",
                    "dataState" to "all",
                    "rowLimit" to PAGE_SIZE,
                    "startRow" to start,
                )
            val page =
                http
                    .post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
            val rows = page?.path("rows")?.takeIf { it.isArray }?.toList().orEmpty()
            rows.forEach { out += row(it, dimensions) }
            start += PAGE_SIZE
        } while (rows.size == PAGE_SIZE && out.size < MAX_ROWS)
        return out
    }

    private fun accessToken(): String {
        token?.let { (value, until) -> if (Instant.now().isBefore(until)) return value }
        val now = Instant.now()
        val assertion =
            Jwts
                .builder()
                .issuer(key.email)
                .audience()
                .single(key.tokenUri)
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))))
                .signWith(key.privateKey, Jwts.SIG.RS256)
                .compact()
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                add("assertion", assertion)
            }
        val res =
            http
                .post()
                .uri(key.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode::class.java)
        val value = res?.path("access_token")?.asText().orEmpty()
        check(value.isNotEmpty()) { "Google token response without access_token" }
        token = value to now.plusSeconds(res!!.path("expires_in").asLong(DEFAULT_TTL_SECONDS) - TOKEN_MARGIN_SECONDS)
        return value
    }

    companion object {
        private const val API = "https://searchconsole.googleapis.com/webmasters/v3"
        private const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/webmasters.readonly"
        private const val PAGE_SIZE = 25_000
        private const val MAX_ROWS = 100_000
        private const val DEFAULT_TTL_SECONDS = 3600L
        private const val TOKEN_MARGIN_SECONDS = 120L
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(90)
        private val PEM_ARMOUR = Regex("-----[A-Z ]+-----")

        val DIMENSIONS: Map<SearchGrain, List<String>> =
            mapOf(
                SearchGrain.SITE to listOf("country", "device"),
                SearchGrain.PAGE to listOf("page", "country", "device"),
                SearchGrain.QUERY to listOf("query", "page", "country", "device"),
            )

        /** The URL-prefix property of a site: `https://www.<host>/`. */
        fun siteUrl(siteId: String): String = "https://www.${SiteIds.HOSTS.entries.first { it.value == siteId }.key}/"

        /** One Search Analytics row; `keys` follow [dimensions]. */
        fun row(
            node: JsonNode,
            dimensions: List<String>,
        ): SearchRow {
            val keys = node.path("keys").map { it.asText() }
            fun key(name: String): String = dimensions.indexOf(name).let { keys.getOrNull(it) }.orEmpty()
            val impressions = node.path("impressions").asInt()
            return SearchRow(
                query = key("query").take(SearchText.QUERY_MAX),
                path = key("page").takeIf { it.isNotEmpty() }?.let(SearchText::path).orEmpty(),
                country = SearchText.country(key("country")),
                device = key("device").uppercase(),
                clicks = node.path("clicks").asInt(),
                impressions = impressions,
                positionSum = node.path("position").asDouble() * impressions,
            )
        }
    }
}
