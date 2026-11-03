package hr.workspace.boat4you.domains.social.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.branding.AiSiteProfile
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/** A boat the AI writes about, with our own photo ids (best first) and the facts it may use. */
data class PickedBoat(
    val id: Long,
    val slug: String,
    val url: String,
    val imageIds: List<Long>,
    val facts: Map<String, Any?>,
)

/**
 * Picks today's boat for a site from our own public API (the same endpoints the sites and the chat use, on localhost):
 * a charter week in the coming season in the site's countries and boat types, boats with photos, not featured on this
 * site in the last 60 days, randomly among the first results. Photos: the boat's own pictures, never layout drawings.
 */
@Component
class SocialBoatPicker(
    private val props: SocialProperties,
    private val objectMapper: ObjectMapper,
) {
    private val client: RestClient by lazy { RestClient.builder().baseUrl(props.selfBaseUrl).build() }

    fun pick(
        site: AiSiteProfile,
        exclude: Set<Long>,
        today: LocalDate,
        random: Random = Random.Default,
    ): PickedBoat? {
        val start = charterWeek(today, random)
        val uri =
            UriComponentsBuilder
                .fromUriString(props.selfBaseUrl)
                .path("/public/yachts")
                .queryParam("startDate", start)
                .queryParam("endDate", start.plusDays(WEEK))
                .queryParam("currency", "EUR")
                .queryParam("size", PAGE)
                .queryParam("page", 0)
                .apply {
                    if (site.countryCodes.isNotEmpty()) queryParam("countryCodes", site.countryCodes.joinToString(","))
                    if (site.vesselTypes.isNotEmpty()) queryParam("vesselType", *site.vesselTypes.map { it.name }.toTypedArray())
                }.build()
                .encode()
                .toUri()
        val body = client.get().uri(uri).retrieve().body(String::class.java) ?: return null
        val candidates =
            objectMapper
                .readTree(body)
                .path("content")
                .filter { it.path("id").asLong(0) > 0 && it.path("id").asLong() !in exclude && it.path("slug").asText().isNotBlank() }
                .filter { it.path("mainImageId").asLong(0) > 0 }
                .take(CANDIDATES)
        return candidates.takeIf { it.isNotEmpty() }?.let { detail(site, it[random.nextInt(it.size)].path("slug").asText()) }
    }

    /** The boat page data of [slug] as the site shows it. */
    fun detail(
        site: AiSiteProfile,
        slug: String,
    ): PickedBoat? {
        val raw = client.get().uri("/public/yachts/{slug}", slug).retrieve().body(String::class.java) ?: return null
        val d = objectMapper.readTree(raw)
        val images =
            d
                .path("yachtImages")
                .filter { !it.path("layout").asBoolean(false) && it.path("kind").asText("PARTNER") in PHOTO_KINDS }
                .sortedWith(compareByDescending<JsonNode> { it.path("mainImage").asBoolean(false) }.thenBy { it.path("position").asInt(Int.MAX_VALUE) })
                .mapNotNull { it.path("id").asLong(0).takeIf { id -> id > 0 } }
        val canonical = d.path("slug").asText(slug)
        val url = site.baseUrl + site.boatPath + canonical
        val facts =
            linkedMapOf<String, Any?>(
                "name" to d.path("name").asText(null),
                "model" to listOfNotNull(d.path("manufacturerName").asText(null), d.path("modelName").asText(null)).joinToString(" ").ifBlank { d.path("model").asText(null) },
                "type" to d.path("vesselType").asText(null),
                "buildYear" to d.path("buildYear").takeIf { it.isNumber }?.asInt(),
                "cabins" to d.path("cabins").takeIf { it.isNumber }?.asInt(),
                "berths" to d.path("berths").takeIf { it.isNumber }?.asInt(),
                "toilets" to d.path("wc").takeIf { it.isNumber }?.asInt(),
                "lengthMeters" to d.path("length").takeIf { it.isNumber }?.decimalValue()?.toPlainString(),
                "homeMarina" to d.path("location").path("name").asText(null)?.substringBefore(" | "),
                "region" to (site.region ?: d.path("location").path("countryCode").asText(null)),
                "charterTypes" to d.path("charterType").map { it.asText() }.ifEmpty { null },
                "crew" to d.path("crewNumber").takeIf { it.isNumber && it.asInt() > 0 }?.asInt(),
                "highlights" to d.path("highlights").asText(null)?.take(HIGHLIGHTS_MAX),
                "pageUrl" to url,
            ).filterValues { it != null && it != "" }
        return if (images.isEmpty()) null else PickedBoat(d.path("id").asLong(), canonical, url, images.take(MAX_IMAGES), facts)
    }

    companion object {
        private const val WEEK = 7L
        private const val PAGE = 60
        private const val CANDIDATES = 25
        private const val MAX_IMAGES = 5
        private const val HIGHLIGHTS_MAX = 600
        private val PHOTO_KINDS = setOf("PARTNER", "")
        private val SEASON = 6..9

        /**
         * A Saturday in the coming sailing season: 3–10 weeks ahead in May–September, else a random Saturday of June to
         * September next season (off-season posts sell the summer).
         */
        fun charterWeek(
            today: LocalDate,
            random: Random,
        ): LocalDate {
            val soon = today.plusWeeks(3L + random.nextInt(8)).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            if (soon.monthValue in 5..9) return soon
            val month = SEASON.first + random.nextInt(SEASON.last - SEASON.first + 1)
            val week = random.nextInt(4).toLong()

            fun inYear(year: Int) = LocalDate.of(year, month, 1).plusWeeks(week).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            // This year's season while it is still ahead, else next year's (an autumn post sells next summer).
            return inYear(today.year).takeIf { it.isAfter(today.plusWeeks(3)) } ?: inYear(today.year + 1)
        }
    }
}
