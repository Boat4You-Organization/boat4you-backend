package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.domains.branding.AiSiteProfile
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.utils.GuletRules
import hr.workspace.boat4you.domains.contactcenter.service.SearchCriteria
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Executes the assistant's tools against our OWN public search API on
 * localhost — the exact same endpoint the website uses, so every price and
 * availability the model can mention comes from live data, never from the
 * model itself. Returns a compact JSON string for the model plus (for
 * search) the card list the web widget renders under the reply.
 */
@Component
class AiChatToolExecutor(
    @Value("\${chat.self-base-url:http://127.0.0.1:8080}") private val selfBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: RestClient = RestClient.builder().baseUrl(selfBaseUrl).build()

    /** Today, for refusing dates in the past (tests fix it). */
    internal var clock: Clock = Clock.systemUTC()

    // labelCode -> equipment id, lazily fetched from our own cached catalogue endpoint.
    @Volatile
    private var amenityIdsByLabel: Map<String, Long> = emptyMap()

    /**
     * [cards] = yacht cards, [blogs] = blog guide links; both rendered by the widget from the message payload.
     * [search] = the criteria `search_yachts` actually sent, set on every return of that tool and never shown to the model or the widget.
     */
    data class ToolOutcome(
        val resultForModel: String,
        val cards: ArrayNode? = null,
        val blogs: ArrayNode? = null,
        val search: SearchCriteria? = null,
    )

    private companion object {
        /** Charter types whose listing may state a crew count (capacity contract v1, 7.2). */
        val CREWED_CHARTER_TYPES = setOf("CREWED", "ALL_INCLUSIVE", "CRUISE")

        /** Longest partner capacity note put into the system prompt. */
        const val CHAT_NOTE_MAX = 60

        /** What the model is told about every gulet (GuletRules: a gulet is never bareboat, Mario 9.10.2026). */
        const val GULET_CHARTER = "crewed - a gulet always sails with its crew, never bareboat or skipper-only"

        /** Tool parameters are model-controlled: only these shapes reach our own API (review B8). */
        val COUNTRY_CODE = Regex("^[A-Z]{2}$")
        val LOCATION = Regex("""^[\p{L}\p{M}\d ,.'\x{2019}()-]{2,60}$""")
        val VESSEL_TYPES = VesselType.entries.map { it.name }.toSet()

        /** Same nights window as the public search (YachtController guard): 3 to 28 nights. */
        const val MIN_NIGHTS = 3
        const val MAX_NIGHTS = 28

        /** A window search covers at most this many Saturday weeks (one API call each) and lists this many boats. */
        const val MAX_WINDOW_WEEKS = 6
        const val MAX_WINDOW_LIST = 12

        /** Boats one search shows (Mario 6.11.2026: give the visitor more options): by default, and at most. */
        const val DEFAULT_RESULTS = 6
        const val MAX_RESULTS = 8

        /**
         * Mario 6.11.2026: a place searches its surroundings too - every marina within this many km of a marina of that
         * name: Lefkas-Preveza 15 km, Split-Trogir 15, Split-Hvar 37, Split-Rogoznica 37, Glyfada-Lavrion 32, Alimos-Lavrion
         * 38; Split-Makarska (53) and Lefkas-Athens (280) stay out.
         */
        const val NEARBY_KM = 40.0

        /** At most this many places, models and search dids in one call (they are query parameters of our own API). */
        const val MAX_PLACES = 4
        const val MAX_MODELS = 4
        const val MAX_DIDS = 40

        /** Cabins a search may ask for: the search API binds them as a 16-bit number. */
        const val MAX_CABINS = 30

        /** "Or similar": the asked model's length plus and minus this many feet (converted to metres for the search API). */
        const val SIMILAR_FT = 4.0

        /** A boat within this many feet of the asked size counts as that size when the picks are spread (42 ft: 40.5-43.5). */
        const val SAME_SIZE_FT = 1.5
        const val FEET_PER_METRE = 3.28084

        /**
         * The search API's minLength / maxLength are metres in every language (11.11.2026; they were feet on EN, which
         * this call relied on by sending no Accept-Language). A lower bound rounds down and an upper bound up, so a
         * boat right on the edge of the asked band is never lost to the conversion.
         */
        fun minLengthMetres(feet: Number): BigDecimal =
            BigDecimal(feet.toDouble() / FEET_PER_METRE).setScale(2, RoundingMode.FLOOR)

        fun maxLengthMetres(feet: Number): BigDecimal =
            BigDecimal(feet.toDouble() / FEET_PER_METRE).setScale(2, RoundingMode.CEILING)

        /** How long a country's marina list (with coordinates) is kept for the nearby lookup. */
        val MARINAS_TTL: java.time.Duration = java.time.Duration.ofHours(6)

        val MODEL = Regex("""^[\p{L}\p{M}\d .'+-]{2,40}$""")
        val SLUG = Regex("^[a-z0-9-]{3,160}$")
        val WORD = Regex("""[\p{L}\d.]+""")
    }

    /** A gulet by the API's vesselType / modelName (GuletRules), for the search rows and the boat page alike. */
    internal fun isGulet(y: JsonNode): Boolean =
        GuletRules.isGulet(
            VesselType.entries.firstOrNull { it.name == y.path("vesselType").asText("") },
            y.path("modelName").asText(null),
        )

    /**
     * [site] is the site the conversation runs on: on a scoped (sister) site the fleet is enforced here, in code (SISTER-1) -
     * a country or place outside it is refused without a search, a vessel type is clamped to the site's, rows outside the
     * fleet are dropped - and the links are the sister page plus boat4you's booking page. [locale] picks the links' language
     * prefix. The unscoped [AiSiteProfile.BOAT4YOU] behaves exactly as before.
     */
    fun searchYachts(
        input: JsonNode,
        site: AiSiteProfile = AiSiteProfile.BOAT4YOU,
        locale: String = "en",
    ): ToolOutcome =
        if (input.path("startDate").asText("").isBlank() && input.path("windowStart").asText("").isNotBlank()) {
            searchWindow(input, site, locale)
        } else {
            searchPeriod(input, site, locale)
        }

    /**
     * A visitor flexible across weeks (Nihad 30.10.2026: five calls, one per week, cut the model's reply short): every
     * Saturday-to-Saturday week inside windowStart..windowEnd (at most [MAX_WINDOW_WEEKS]) is searched with the same
     * filters and the boats are merged, each at its cheapest week. A refusal (country, place, dates) is the first week's.
     */
    private fun searchWindow(
        input: JsonNode,
        site: AiSiteProfile,
        locale: String,
    ): ToolOutcome {
        val asked = runCatching { LocalDate.parse(input.path("windowStart").asText("")) }.getOrNull()
        val to = runCatching { LocalDate.parse(input.path("windowEnd").asText("")) }.getOrNull()
        val today = LocalDate.now(clock)
        if (asked != null && to != null && !to.isAfter(today)) return pastDates(asked, to, today)
        // A window that began in the past: only its weeks still ahead.
        val from = asked?.let { maxOf(it, today) }
        val saturdays =
            if (from == null || to == null) {
                emptyList()
            } else {
                generateSequence(from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))) { it.plusWeeks(1) }
                    .takeWhile { !it.plusDays(7).isAfter(to) }
                    .toList()
            }
        if (saturdays.isEmpty()) {
            return ToolOutcome("""{"error":"no Saturday-to-Saturday week fits in this window - use YYYY-MM-DD and a window of at least one week"}""")
        }
        val limit = input.path("limit").asInt(MAX_RESULTS).coerceIn(1, MAX_RESULTS)
        val desc = input.path("sortByPrice").asText("asc") == "desc"
        val best = LinkedHashMap<String, Pair<JsonNode, JsonNode>>() // slug -> (boat for the model, card)
        val weeks = objectMapper.createArrayNode()
        var first: JsonNode? = null
        var firstSearch: SearchCriteria? = null
        var totalAvailable = 0
        val cache = WindowCache() // one place and model lookup for all weeks of the window
        for (saturday in saturdays.take(MAX_WINDOW_WEEKS)) {
            val week =
                (input.deepCopy<JsonNode>() as ObjectNode).apply {
                    remove(listOf("windowStart", "windowEnd"))
                    put("startDate", saturday.toString())
                    put("endDate", saturday.plusDays(7).toString())
                    put("limit", MAX_RESULTS)
                }
            val outcome = searchPeriod(week, site, locale, cache)
            val parsed = objectMapper.readTree(outcome.resultForModel)
            if (parsed.has("error")) return outcome
            if (first == null) {
                first = parsed
                firstSearch = outcome.search
            }
            totalAvailable += parsed.path("totalAvailable").asInt(0)
            weeks.addObject().apply {
                put("startDate", saturday.toString())
                put("endDate", saturday.plusDays(7).toString())
                put("matches", parsed.path("matches").asInt(0))
                put("totalAvailable", parsed.path("totalAvailable").asInt(0))
            }
            // The model's boats and the cards are added in the same loop of searchPeriod, so index i is one boat.
            val cards = outcome.cards ?: continue
            parsed.path("yachts").forEachIndexed { i, boat ->
                val card = cards.get(i) ?: return@forEachIndexed
                val slug = card.path("slug").asText()
                val kept = best[slug]
                if (kept == null || boat.path("totalPriceEur").asInt() < kept.first.path("totalPriceEur").asInt()) best[slug] = boat to card
            }
        }
        val price = compareBy<Pair<JsonNode, JsonNode>> { it.first.path("totalPriceEur").asInt() }
        val ranked = best.values.sortedWith(if (desc) price.reversed() else price)
        val result =
            objectMapper.createObjectNode().apply {
                put("matches", ranked.size)
                put("totalAvailable", totalAvailable)
                put("sortedBy", if (desc) "price descending" else "price ascending")
                put("window", "each boat at its cheapest Saturday-to-Saturday week in $from - $to")
                listOf("searchedArea", "equipmentNote", "vesselNote", "modelFilter", "modelNote").forEach { key -> first?.get(key)?.let { set<JsonNode>(key, it) } }
                set<JsonNode>("weeks", weeks)
                if (saturdays.size > MAX_WINDOW_WEEKS) {
                    put(
                        "weeksNote",
                        "searched the first $MAX_WINDOW_WEEKS weeks of ${saturdays.size}; ask the visitor whether to check the later weeks too",
                    )
                }
                putArray("yachts").addAll(ranked.take(MAX_WINDOW_LIST).map { it.first })
                if (ranked.isEmpty()) put("hint", "no results in any week of the window - suggest relaxing filters (budget, cabins, equipment) or a nearby area")
            }
        val cards = objectMapper.createArrayNode().addAll(ranked.take(limit).map { it.second })
        return ToolOutcome(
            objectMapper.writeValueAsString(result),
            if (ranked.isEmpty()) null else cards,
            search = firstSearch?.copy(startDate = from, endDate = to, resultCount = ranked.size, totalAvailable = totalAvailable),
        )
    }

    private fun searchPeriod(
        input: JsonNode,
        site: AiSiteProfile,
        locale: String,
        cache: WindowCache? = null,
    ): ToolOutcome {
        val countryCode = input.path("countryCode").asText("").trim().uppercase().take(2).takeIf { COUNTRY_CODE.matches(it) } ?: ""
        val startDate = input.path("startDate").asText("")
        val endDate = input.path("endDate").asText("")
        val vesselType = input.path("vesselType").asText("").trim().uppercase().takeIf { it in VESSEL_TYPES } ?: ""
        // One place (`location`) or several (`locations`, Mario 6.11.2026: "Lefkada or maybe Preveza"); a polluted name is dropped.
        val askedPlaces =
            (listOf(input.path("location")) + input.path("locations").toList())
                .mapNotNull { it.asText("").trim().takeIf { name -> LOCATION.matches(name) } }
                .distinctBy { it.lowercase() }
                .take(MAX_PLACES)
        val location = askedPlaces.joinToString(", ")
        val models =
            (listOf(input.path("model")) + input.path("models").toList())
                .mapNotNull { it.asText("").trim().takeIf { name -> MODEL.matches(name) } }
                .distinctBy { it.lowercase() }
                .take(MAX_MODELS)
        val orSimilar = input.path("orSimilar").asBoolean(false)
        val lengthFt = input.path("lengthFt").asInt(0).takeIf { it in 10..200 }
        val minLengthFt = input.path("minLengthFt").asInt(0).takeIf { it in 10..200 }
        val maxLengthFt = input.path("maxLengthFt").asInt(0).takeIf { it in 10..200 }
        val minCabins = input.path("minCabins").asInt(0).coerceIn(0, MAX_CABINS)
        val maxCabins = input.path("maxCabins").asInt(0).takeIf { it in 1..MAX_CABINS && it >= minCabins }
        val minPersons = input.path("minPersons").asInt(0)
        val maxTotalPriceEur = input.path("maxTotalPriceEur").asInt(0)
        val minTotalPriceEur = input.path("minTotalPriceEur").asInt(0)
        val sortByPrice = input.path("sortByPrice").asText("asc").let { if (it == "desc") "desc" else "asc" }
        val limit = input.path("limit").asInt(DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)
        val equipment = input.path("equipment").mapNotNull { it.asText(null) }.filter { it.isNotBlank() }

        // The vessel types this call asks our API for: on a site that sells every type what the model asked for; on a site
        // that sells some types the asked one if the site sells it, else all of the site's (clamped, and the model is told).
        val types =
            if (site.vesselTypes.isEmpty()) {
                listOfNotNull(vesselType.ifBlank { null })
            } else {
                site.vesselTypes.map { it.name }.let { allowed -> if (vesselType in allowed) listOf(vesselType) else allowed }
            }

        // The whitelisted values this call works with, for the contact center's search log; blank or <= 0 means "not set".
        fun criteria(
            resolved: ResolvedPlace?,
            sentEquipment: List<String>,
            resultCount: Int,
            totalAvailable: Int?,
        ) = SearchCriteria(
            countryCode = countryCode.takeIf { it.isNotBlank() },
            location = location.takeIf { it.isNotBlank() }?.take(SearchCriteria.LOCATION_MAX),
            did = resolved?.did?.toLongOrNull(),
            startDate = SearchCriteria.parseDate(startDate),
            endDate = SearchCriteria.parseDate(endDate),
            minPersons = minPersons.takeIf { it > 0 },
            minCabins = minCabins.takeIf { it > 0 },
            minPriceEur = minTotalPriceEur.takeIf { it > 0 },
            maxPriceEur = maxTotalPriceEur.takeIf { it > 0 },
            vesselType = types.joinToString(",").ifEmpty { null },
            equipment = sentEquipment,
            resultCount = resultCount,
            totalAvailable = totalAvailable,
            searchedArea = resolved?.let { "${it.name} (${it.type.lowercase()})".take(120) },
        )

        if (countryCode.isBlank() || startDate.isBlank() || endDate.isBlank()) {
            return ToolOutcome("""{"error":"countryCode, startDate and endDate are required"}""", search = criteria(null, emptyList(), 0, null))
        }
        val days = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)).toInt()
        }.getOrDefault(0)
        if (days !in MIN_NIGHTS..MAX_NIGHTS) {
            return ToolOutcome(
                """{"error":"invalid date range — use YYYY-MM-DD, $MIN_NIGHTS to $MAX_NIGHTS nights"}""",
                search = criteria(null, emptyList(), 0, null),
            )
        }
        val today = LocalDate.now(clock)
        if (LocalDate.parse(startDate).isBefore(today)) {
            return pastDates(LocalDate.parse(startDate), LocalDate.parse(endDate), today).copy(search = criteria(null, emptyList(), 0, null))
        }

        if (site.scoped && countryCode !in site.countryCodes) {
            val refusal =
                objectMapper.createObjectNode().apply {
                    put("error", "outside this site's fleet")
                    put("siteCountries", site.countryCodes.joinToString(","))
                    put("hint", fleetHint(site))
                }
            return ToolOutcome(objectMapper.writeValueAsString(refusal), search = criteria(null, emptyList(), 0, null))
        }

        // "Split" must search the Split REGION, not all of Croatia (Mario 20.7.2026:
        // bot answered a Split request with boats from Punat). Resolve the place via
        // the same autocomplete the website's "Where" field uses and pass its did.
        val resolutions =
            askedPlaces.map { place ->
                cache?.places?.getOrPut("$countryCode|$place") { resolveDestination(place, countryCode, site) }
                    ?: resolveDestination(place, countryCode, site)
            }
        // Refused only when every place the visitor named lies outside the fleet; one inside is searched.
        if (resolutions.isNotEmpty() && resolutions.all { it.elsewhereCountry != null }) {
            val refusal =
                objectMapper.createObjectNode().apply {
                    put("error", "'$location' is in ${resolutions.first().elsewhereCountry}, outside this site's fleet")
                    put("hint", fleetHint(site))
                }
            return ToolOutcome(objectMapper.writeValueAsString(refusal), search = criteria(null, emptyList(), 0, null))
        }
        val found = resolutions.mapNotNull { it.place }
        // The cc_search log keeps one area: the first place, named as it was searched.
        val resolved = found.firstOrNull()?.let { if (found.size == 1) it else it.copy(name = found.joinToString(" + ") { p -> p.name }) }
        val dids = found.flatMap { it.dids.take(MAX_DIDS / found.size) }.distinct()
        val equipmentIds = equipment.mapNotNull { amenityId(it) }
        val sentEquipment = equipment.filter { amenityId(it) != null }

        // A model is a filter (its catalogue variants: "Lagoon 46" is also "Lagoon 46 Fly"); with orSimilar its length band.
        val modelIds = models.associateWith { m -> cache?.models?.getOrPut(m.lowercase()) { resolveModel(m) } ?: resolveModel(m) }
        val mids = modelIds.values.flatten().distinct()
        val askedModelNames = modelIds.filterValues { it.isNotEmpty() }.keys
        // "Or similar" (review 6.11.2026): the band spans every asked model - one reference boat each, Lagoon 46 to 52 -
        // and the asked models are searched on their own first, so cheaper similar boats never push them off the page.
        val references =
            if (!orSimilar) {
                emptyList()
            } else {
                modelIds.filterValues { it.isNotEmpty() }.mapNotNull { (name, ids) ->
                    cache?.references?.getOrPut(name.lowercase()) { referenceBoat(ids, listOf(name)) } ?: referenceBoat(ids, listOf(name))
                }
            }
        val lengths = references.map { it.path("length").asDouble(0.0) * FEET_PER_METRE }.filter { it > 0 }
        // Mario 6.11.2026: "a 42 ft catamaran" gets 40 and 46 ft boats too, "46 ft" 42 to 48 - a size is a band around it.
        val band =
            lengths.takeIf { it.isNotEmpty() }?.let { kotlin.math.floor(it.min() - SIMILAR_FT).toInt() to kotlin.math.ceil(it.max() + SIMILAR_FT).toInt() }
                ?: lengthFt?.takeIf { mids.isEmpty() }?.let { (it - SIMILAR_FT).toInt() to (it + SIMILAR_FT).toInt() }
        val targetFt = lengths.takeIf { it.isNotEmpty() }?.average() ?: lengthFt?.toDouble()?.takeIf { band != null }
        val similarType = references.firstOrNull()?.path("vesselType")?.asText("")?.takeIf { it in VESSEL_TYPES && types.isEmpty() }

        fun uri(byModel: Boolean) =
            UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts")
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .queryParam("currency", "EUR")
                .queryParam("size", 50)
                .queryParam("page", 0)
                .queryParam("sortBy", sortByPrice)
                .apply {
                    if (dids.isNotEmpty()) queryParam("did", *dids.toTypedArray()) else queryParam("countryCodes", countryCode)
                    if (types.isNotEmpty()) queryParam("vesselType", *types.toTypedArray())
                    similarType?.let { queryParam("vesselType", it) }
                    if (minCabins > 0) queryParam("minCabins", minCabins)
                    maxCabins?.let { queryParam("maxCabins", it) }
                    if (minPersons > 0) queryParam("minPersons", minPersons)
                    if (equipmentIds.isNotEmpty()) queryParam("amenities", equipmentIds)
                    when {
                        byModel -> queryParam("mid", *mids.toTypedArray())
                        band != null ->
                            queryParam("minLength", minLengthMetres(band.first))
                                .queryParam("maxLength", maxLengthMetres(band.second))
                        else -> {
                            minLengthFt?.let { queryParam("minLength", minLengthMetres(it)) }
                            maxLengthFt?.let { queryParam("maxLength", maxLengthMetres(it)) }
                        }
                    }
                }
                .build().encode().toUri()

        val pages =
            try {
                listOfNotNull(
                    if (mids.isNotEmpty()) objectMapper.readTree(client.get().uri(uri(byModel = true)).retrieve().body(String::class.java)) else null,
                    if (mids.isEmpty() || band != null) objectMapper.readTree(client.get().uri(uri(byModel = false)).retrieve().body(String::class.java)) else null,
                )
            } catch (e: Exception) {
                log.warn("chat search_yachts failed: ${e.message}")
                return ToolOutcome(
                    """{"error":"search temporarily unavailable, apologize and suggest trying again"}""",
                    search = criteria(resolved, sentEquipment, 0, null),
                )
            }

        val totalAvailable = pages.maxOf { it.path("page").path("totalElements").asInt(it.path("content").size()) }
        val forModel = objectMapper.createArrayNode()
        val cards = objectMapper.createArrayNode()
        var taken = 0
        // "Or similar": the boats of the asked models first, then the similar ones, each group in the API's price order.
        val ordered =
            if (pages.size == 1) {
                pages.single().path("content").toList()
            } else {
                pages.flatMap { it.path("content").toList() }.distinctBy { it.path("slug").asText() }
                    .sortedBy { if (isAskedModel(it, askedModelNames)) 0 else 1 }
            }
        val eligible =
            ordered.mapNotNull { y ->
                if (!inFleet(site, y)) return@mapNotNull null
                val offer = pricedOffer(y, days, startDate, endDate) ?: return@mapNotNull null
                if (maxTotalPriceEur in 1 until offer.total) return@mapNotNull null
                if (minTotalPriceEur > 0 && offer.total < minTotalPriceEur) return@mapNotNull null
                y to offer
            }
        val picked = targetFt?.let { spread(eligible, it, askedModelNames, limit) } ?: eligible
        for ((y, offer) in picked) {
            if (taken >= limit) break
            val total = offer.total
            val slug = y.path("slug").asText()
            val label = listOf(y.path("modelName").asText(""), y.path("name").asText(""))
                .filter { it.isNotBlank() }.joinToString(" ")

            forModel.add(
                objectMapper.createObjectNode().apply {
                    put("yacht", label)
                    put("totalPriceEur", total)
                    put("startDate", offer.from)
                    put("endDate", offer.to)
                    put("nights", offer.nights)
                    put("dates", offer.dates)
                    if (band != null && askedModelNames.isNotEmpty()) put("match", if (isAskedModel(y, askedModelNames)) "asked model" else "similar size")
                    y.path("length").asDouble(0.0).takeIf { it > 0 && band != null }?.let { put("lengthFt", (it * FEET_PER_METRE).roundToInt()) }
                    // Partner figures only when known (> 0) - an absent key, never a 0 the model would repeat.
                    putPositive("cabins", y.path("cabins"))
                    putPositive("berths", y.path("berths"))
                    putPositive("wc", y.path("wc"))
                    putPositive("maxPeopleOnBoard", y.path("maxPersons"))
                    put("year", y.path("buildYear").asInt(0))
                    put("base", y.path("location").path("name").asText(""))
                    if (isGulet(y)) put("charter", GULET_CHARTER)
                    val amenities = y.path("amenityKeys").mapNotNull { it.asText(null) }
                    if (amenities.isNotEmpty()) put("topAmenities", amenities.joinToString(","))
                    if (site.scoped) {
                        put("url", sisterUrl(site, locale, slug, offer.from, offer.to))
                        put("bookUrl", bookUrl(site, locale, slug, offer.from, offer.to))
                    } else {
                        put("url", "https://www.boat4you.com/boat/$slug?startDate=${offer.from}&endDate=${offer.to}&currency=EUR")
                    }
                },
            )
            cards.add(
                objectMapper.createObjectNode().apply {
                    put("name", label)
                    put("slug", slug)
                    put("imageId", y.path("mainImageId").asLong(0))
                    put("totalPriceEur", total)
                    put("days", offer.nights)
                    putPositive("cabins", y.path("cabins"))
                    putPositive("berths", y.path("berths"))
                    putPositive("wc", y.path("wc"))
                    putPositive("maxPersons", y.path("maxPersons"))
                    put("year", y.path("buildYear").asInt(0))
                    put("location", y.path("location").path("name").asText(""))
                    put("startDate", offer.from)
                    put("endDate", offer.to)
                    put("dates", offer.dates)
                    // A sister site's widget cannot build its own link from the slug (boat4you's does, WEB-7).
                    if (site.scoped) {
                        put("url", sisterUrl(site, locale, slug, offer.from, offer.to))
                        put("bookUrl", bookUrl(site, locale, slug, offer.from, offer.to))
                    }
                },
            )
            taken++
        }

        val result = objectMapper.createObjectNode().apply {
            put("matches", taken)
            // Total matching the server-side filters, BEYOND the boats shown — the model must
            // never claim "these are all there is" from one page (Mario 20.7: "daj mi skuplje"
            // was answered with 'no pricier ones exist' while 24 more existed).
            put("totalAvailable", totalAvailable)
            put("sortedBy", if (sortByPrice == "desc") "price descending" else "price ascending")
            when {
                found.isNotEmpty() -> {
                    val missing = askedPlaces.filterIndexed { i, _ -> resolutions[i].place == null }
                    put(
                        "searchedArea",
                        found.joinToString("; ") { it.describe() } +
                            if (missing.isEmpty()) "" else "; not found and not searched: ${missing.joinToString(", ")}",
                    )
                }
                location.isNotBlank() ->
                    put("searchedArea", "place '$location' not found — searched the whole country; tell the visitor honestly")
            }
            if (askedModelNames.isNotEmpty()) {
                put(
                    "modelFilter",
                    when {
                        band != null ->
                            "${askedModelNames.joinToString(", ")} or similar: ${band.first}-${band.second} ft" +
                                " - each boat's match says whether it is the asked model or a similar size"
                        else -> "only ${askedModelNames.joinToString(", ")} (with its catalogue variants)"
                    },
                )
            }
            (models - askedModelNames).takeIf { it.isNotEmpty() }?.let {
                put(
                    "modelNote",
                    "could not match ${it.joinToString(", ")} to our catalogue, so it was NOT filtered and the boats listed may be other " +
                        "models - never call them that model and never say we do not have it; search again with the manufacturer " +
                        "first (\"Jeanneau Sun Odyssey 410\") or by size (minLengthFt/maxLengthFt)",
                )
            }
            if (equipment.isNotEmpty() && equipmentIds.size < equipment.size) {
                put("equipmentNote", "some requested equipment filters are unknown and were ignored: " +
                    equipment.filter { amenityId(it) == null }.joinToString(","))
            }
            if (vesselType.isNotBlank() && vesselType !in types) {
                put("vesselNote", "$vesselType is not offered on this site; searched ${site.fleetLabel} instead")
            }
            set<JsonNode>("yachts", forModel)
            if (taken == 0) put("hint", "no results — suggest relaxing filters (dates, budget, cabins, equipment) or a nearby area")
        }
        return ToolOutcome(
            objectMapper.writeValueAsString(result),
            if (taken > 0) cards else null,
            search = criteria(resolved, sentEquipment, taken, totalAvailable),
        )
    }

    /**
     * Mario 6.11.2026: "always varied around what they ask, never more than 8". From the boats of a size band (price
     * order): the asked models first, at most half of the [limit]; then in turn one at the asked size, one smaller, one
     * bigger, each the cheapest of a model not shown yet (repeats only when the models run out). Boats without a known
     * length come last.
     */
    private fun spread(
        boats: List<Pair<JsonNode, PricedOffer>>,
        targetFt: Double,
        askedModels: Collection<String>,
        limit: Int,
    ): List<Pair<JsonNode, PricedOffer>> {
        val chosen = mutableListOf<Pair<JsonNode, PricedOffer>>()
        val asked = boats.filter { isAskedModel(it.first, askedModels) }
        chosen += asked.take((limit + 1) / 2)
        fun feet(b: Pair<JsonNode, PricedOffer>) = b.first.path("length").asDouble(0.0) * FEET_PER_METRE
        fun model(b: Pair<JsonNode, PricedOffer>) = "${b.first.path("manufacturerName").asText("")} ${b.first.path("modelName").asText("")}".trim().lowercase()
        val rest = boats.filter { it !in chosen && feet(it) > 0 }
        val buckets =
            listOf(
                rest.filter { kotlin.math.abs(feet(it) - targetFt) <= SAME_SIZE_FT },
                rest.filter { feet(it) - targetFt < -SAME_SIZE_FT },
                rest.filter { feet(it) - targetFt > SAME_SIZE_FT },
            ).map { it.toMutableList() }
        val models = chosen.map { model(it) }.toMutableSet()
        while (chosen.size < limit && buckets.any { it.isNotEmpty() }) {
            for (bucket in buckets) {
                if (chosen.size >= limit) break
                val next = bucket.firstOrNull { model(it) !in models } ?: bucket.firstOrNull().takeIf { buckets.all { b -> b.none { model(it) !in models } } }
                next ?: continue
                bucket.remove(next)
                chosen += next
                models += model(next)
            }
        }
        chosen += boats.filter { it !in chosen }
        return chosen
    }

    /** The period total of one search row and the window it is for; [dates] = "exact" | "nearby". */
    internal data class PricedOffer(val total: Int, val nights: Int, val from: String, val to: String, val dates: String)

    /**
     * Headline convention: prices are shown as the TOTAL for the period, never per-day. clientPriceEur is per day of
     * the offer the card shows and numberOfDays that offer's nights (a tiled long charter carries the requested
     * nights): the web card's own per-day x numberOfDays, so the chat never quotes a 14-night offer at 7 nights.
     * The card and its link carry the offer's own window, "nearby" when it is not the requested one. Missing fields
     * fall back to the requested period; null for a row without a positive price.
     */
    internal fun pricedOffer(
        y: JsonNode,
        days: Int,
        startDate: String,
        endDate: String,
    ): PricedOffer? {
        val perDay = y.path("clientPriceEur").decimalValue()
        if (perDay <= BigDecimal.ZERO) return null
        val nights = y.path("numberOfDays").asInt(0).takeIf { it > 0 } ?: days
        val total = perDay.multiply(BigDecimal(nights)).setScale(0, RoundingMode.HALF_UP).toInt()
        val from = isoDate(y.path("offerDateFrom")) ?: startDate
        val to = isoDate(y.path("offerDateTo")) ?: endDate
        return PricedOffer(total, nights, from, to, if (from == startDate && to == endDate) "exact" else "nearby")
    }

    /** A search row's ISO date (yyyy-MM-dd), or null when it is missing or not a date. */
    private fun isoDate(node: JsonNode): String? =
        node.asText("").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

    /** The lookups of one window search, made once for all its weeks (review 6.11.2026: models were resolved per week). */
    private class WindowCache {
        val places = mutableMapOf<String, Resolution>()
        val models = mutableMapOf<String, List<Long>>()
        val references = mutableMapOf<String, JsonNode?>()
    }

    /**
     * A searched place: [did] is the first search id (the cc_search log keeps it), [dids] every id searched for it. A
     * REGION is its own id (the search expands it to its marinas); a marina name is every marina of that name ([marinas])
     * and the marinas [nearby] them (Mario 6.11.2026).
     */
    private data class ResolvedPlace(
        val did: String,
        val name: String,
        val type: String,
        val dids: List<String> = listOf(did),
        val marinas: List<String> = emptyList(),
        val nearby: List<String> = emptyList(),
    ) {
        fun describe(): String =
            when {
                marinas.size <= 1 && nearby.isEmpty() -> "$name (${type.lowercase()})"
                marinas.isEmpty() -> "$name (${type.lowercase()}; nearby: ${nearby.joinToString(", ")})"
                else -> "$name (marinas: ${marinas.joinToString(", ")}" + (if (nearby.isEmpty()) "" else "; nearby: ${nearby.joinToString(", ")}") + ")"
            }
    }

    /**
     * The result of a place lookup: the best [place] inside the requested country, or, only on a scoped site that has no
     * such place, the [elsewhereCountry] the name was found in (the search is then refused, not widened). It is refused
     * only when every country the name was found in is outside the site's fleet; a name found in another fleet country
     * falls through to the whole-country search of the requested country with the place-not-found note.
     */
    private data class Resolution(val place: ResolvedPlace?, val elsewhereCountry: String?)

    /**
     * Same lookup the website's "Where" autocomplete uses. Picks the best row for the
     * bot: prefer a REGION (server-side it expands to all its marinas), then a MARINA,
     * then a COUNTRY; rows from another country than the one the visitor asked for are
     * skipped so "Split" never resolves outside HR when countryCode=HR. A row without a
     * country counts for the unscoped site only: a scoped site must not guess where it is.
     */
    private fun resolveDestination(query: String, countryCode: String, site: AiSiteProfile): Resolution {
        val body = try {
            client.get()
                .uri(
                    UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/locations")
                        .queryParam("name", query).queryParam("size", 20).build().encode().toUri(),
                )
                .retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat location resolve failed for '$query': ${e.message}")
            return Resolution(null, null)
        }
        val content = objectMapper.readTree(body).path("content")
        val rows = content
            .filter { r ->
                val cc = r.path("countryCode").asText("")
                cc.equals(countryCode, ignoreCase = true) || (!site.scoped && (cc.isBlank() || countryCode.isBlank()))
            }
        val best = rows.minByOrNull {
            when (it.path("locationType").asText()) {
                "REGION" -> 0
                "MARINA" -> 1
                else -> 2
            }
        } ?: return Resolution(
            null,
            if (!site.scoped) {
                null
            } else {
                content.map { it.path("countryCode").asText("").trim().uppercase() }.filter { it.isNotBlank() }.let { codes ->
                    if (codes.any { it in site.countryCodes }) null else codes.firstOrNull()
                }
            },
        )
        // Mario 6.11.2026: every place is its surroundings - "Split" is also Trogir, Kaštela, Šolta and Brač, "Athens" also
        // Lavrion, "Lefkas" also Preveza. The lookup that finds a REGION finds the marinas of that name too (with their
        // coordinates): the region (the search expands it), every marina of that name, and every marina of the country
        // within NEARBY_KM of one of those, nearest first.
        val named = rows.filter { it.path("locationType").asText() == "MARINA" && idsOf(it).isNotEmpty() }
        val region = best.takeIf { it.path("locationType").asText() == "REGION" }
        if (region == null && named.isEmpty()) {
            return Resolution(ResolvedPlace(did = best.path("id").asText(), name = best.path("name").asText(), type = best.path("locationType").asText()), null)
        }
        val namedIds = named.flatMap { idsOf(it) }.toSet()
        val country = (named.firstOrNull() ?: region)!!.path("countryCode").asText("").uppercase()
        val nearby =
            if (named.isEmpty()) {
                emptyList()
            } else {
                // Only marinas with boats, and none the region already covers (Split 6.11.2026: forty empty or duplicate
                // Kaštela and Stobreč rows filled the dids before Trogir, Šolta or Brač came).
                val withBoats = locationsWithBoats(null)
                val inRegion = region?.path("id")?.asText()?.removePrefix("r-")?.toLongOrNull()?.let { locationsWithBoats(it) }.orEmpty()
                marinasOf(country)
                    .filter { m -> idsOf(m).none { it in namedIds || it in inRegion } && (withBoats.isEmpty() || idsOf(m).any { it in withBoats }) }
                    .mapNotNull { m -> named.mapNotNull { n -> distanceKm(n, m) }.minOrNull()?.takeIf { it <= NEARBY_KM }?.let { m to it } }
                    .sortedBy { it.second }
                    .map { it.first }
            }
        val dids = (listOfNotNull(region?.path("id")?.asText()) + (named + nearby).flatMap { idsOf(it) }).filter { it.isNotBlank() }.distinct()
        return Resolution(
            ResolvedPlace(
                did = dids.first(),
                name = region?.path("name")?.asText() ?: if (named.size == 1 && nearby.isEmpty()) named.first().path("name").asText() else query,
                type = if (region != null) "REGION" else "MARINA",
                dids = dids,
                marinas = if (region != null) emptyList() else named.map { it.path("name").asText() },
                nearby = nearby.map { it.path("name").asText() },
            ),
            null,
        )
    }

    /**
     * The single search ids of a location row: a merged dual-source marina is "l-1865,l-259", and the search splits a
     * comma only when it is the one did value (review 6.11.2026), so every id goes as its own did.
     */
    private fun idsOf(row: JsonNode): List<String> = row.path("id").asText("").split(',').map { it.trim() }.filter { it.isNotBlank() }

    private val marinas = java.util.concurrent.ConcurrentHashMap<String, Pair<java.time.Instant, List<JsonNode>>>()

    /** The marinas of [country] with their coordinates (`/public/marinas`), kept [MARINAS_TTL]; empty when it fails. */
    private fun marinasOf(country: String): List<JsonNode> {
        if (!COUNTRY_CODE.matches(country)) return emptyList()
        val now = java.time.Instant.now(clock)
        marinas[country]?.takeIf { it.first.isAfter(now.minus(MARINAS_TTL)) }?.let { return it.second }
        val list =
            try {
                client.get()
                    .uri(UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/marinas").queryParam("countryCode", country).build().encode().toUri())
                    .retrieve().body(String::class.java)
                    .let { objectMapper.readTree(it) }
                    .filter { it.path("lat").isNumber && it.path("lon").isNumber && idsOf(it).isNotEmpty() }
            } catch (e: Exception) {
                log.warn("chat marinas of {} failed: {}", country, e.message)
                return emptyList()
            }
        marinas[country] = now to list
        return list
    }

    private val boatLocations = java.util.concurrent.ConcurrentHashMap<Long, Pair<java.time.Instant, Set<String>>>()

    /**
     * The ids of the locations that have boats (`/public/locations-count`), all of them or one region's ([regionId]), kept
     * [MARINAS_TTL]; empty when the lookup fails (the nearby marinas are then not narrowed).
     */
    private fun locationsWithBoats(regionId: Long?): Set<String> {
        val key = regionId ?: 0L
        val now = java.time.Instant.now(clock)
        boatLocations[key]?.takeIf { it.first.isAfter(now.minus(MARINAS_TTL)) }?.let { return it.second }
        val ids =
            try {
                client.get()
                    .uri(
                        UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/locations-count")
                            .apply { regionId?.let { queryParam("regionId", it) } }.build().encode().toUri(),
                    )
                    .retrieve().body(String::class.java)
                    .let { objectMapper.readTree(it) }
                    .filter { it.path("yachtCount").asInt(0) > 0 }
                    .flatMap { idsOf(it) }
                    .toSet()
            } catch (e: Exception) {
                log.warn("chat locations with boats ({}) failed: {}", regionId, e.message)
                return emptySet()
            }
        boatLocations[key] = now to ids
        return ids
    }

    /** Great-circle distance between two location rows in km, or null when one has no coordinates. */
    private fun distanceKm(a: JsonNode, b: JsonNode): Double? {
        if (!a.path("lat").isNumber || !a.path("lon").isNumber || !b.path("lat").isNumber || !b.path("lon").isNumber) return null
        val la1 = Math.toRadians(a.path("lat").asDouble())
        val la2 = Math.toRadians(b.path("lat").asDouble())
        val dLa = la2 - la1
        val dLo = Math.toRadians(b.path("lon").asDouble() - a.path("lon").asDouble())
        val h = kotlin.math.sin(dLa / 2).let { it * it } + kotlin.math.cos(la1) * kotlin.math.cos(la2) * kotlin.math.sin(dLo / 2).let { it * it }
        return 2 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    /**
     * The catalogue model ids of a model the visitor named ("Lagoon 46", "Bali 4.2"): the manufacturer by the name's first
     * word, then its models whose name has every other word of the name as a whole word - "Lagoon 46" is also "Lagoon 46
     * Fly", never "Lagoon 460". Empty when the manufacturer or the model is not in the catalogue, or the lookup fails.
     */
    private fun resolveModel(name: String): List<Long> {
        val words = WORD.findAll(name).map { it.value }.toList()
        if (words.size < 2) return emptyList()
        return try {
            val makers =
                client.get()
                    .uri(UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/catalogue/manufacturers").queryParam("name", words.first()).build().encode().toUri())
                    .retrieve().body(String::class.java)
                    .let { objectMapper.readTree(it).path("content").toList() }
            if (makers.isEmpty()) return emptyList()
            val makerWords = makers.flatMap { m -> WORD.findAll(m.path("name").asText("")).map { it.value.lowercase() }.toList() }.toSet()
            val rest = words.drop(1).filter { it.lowercase() !in makerWords }
            if (rest.isEmpty()) return emptyList()
            client.get()
                .uri(
                    UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/catalogue/models")
                        .queryParam("manufacturerIds", makers.map { it.path("id").asLong() }.joinToString(","))
                        .queryParam("name", rest.joinToString(" "))
                        .queryParam("size", 50)
                        .build().encode().toUri(),
                )
                .retrieve().body(String::class.java)
                .let { objectMapper.readTree(it).path("content") }
                .filter { m -> hasWords(m.path("name").asText(""), rest) }
                .map { it.path("id").asLong() }
                .filter { it > 0 }
        } catch (e: Exception) {
            log.warn("chat model lookup failed for '{}': {}", name, e.message)
            emptyList()
        }
    }

    /** True when every one of [words] is a whole word of [text], ignoring case ("4.2" in "Bali 4.2", "46" not in "460"). */
    private fun hasWords(text: String, words: List<String>): Boolean {
        val have = WORD.findAll(text).map { it.value.lowercase() }.toSet()
        return words.all { it.lowercase() in have }
    }

    /** A search row of one of the visitor's [askedModels] ("Lagoon 46" for a "Lagoon 46" row, never for a "Lagoon 460"). */
    private fun isAskedModel(y: JsonNode, askedModels: Collection<String>): Boolean {
        val label = "${y.path("manufacturerName").asText("")} ${y.path("modelName").asText("")}"
        return askedModels.any { asked -> hasWords(label, WORD.findAll(asked).map { it.value }.toList()) }
    }

    /** One boat of the [asked] models, any dates, for its length and type (the "or similar" band); null when there is none. */
    private fun referenceBoat(mids: List<Long>, asked: Collection<String>): JsonNode? =
        try {
            client.get()
                .uri(
                    UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts")
                        .queryParam("mid", *mids.toTypedArray()).queryParam("currency", "EUR").queryParam("size", 5).queryParam("page", 0)
                        .build().encode().toUri(),
                )
                .retrieve().body(String::class.java)
                .let { body ->
                    val rows = objectMapper.readTree(body).path("content").filter { row -> row.path("length").asDouble(0.0) > 0 }
                    rows.firstOrNull { isAskedModel(it, asked) } ?: rows.firstOrNull()
                }
        } catch (e: Exception) {
            log.warn("chat reference boat lookup failed: {}", e.message)
            null
        }

    /** What the model is told when a search or a place lies outside the fleet of a scoped [site]. */
    private fun fleetHint(site: AiSiteProfile): String =
        "${site.siteName} only offers ${site.fleetLabel} in ${site.region}. Tell the visitor honestly and suggest www.boat4you.com, " +
            "which has boats in 40+ countries; never invent boats."

    /**
     * False for a boat (a search row or a boat page's detail) that a scoped [site] does not sell: its base country is not
     * one of the site's, or the site sells only some vessel types and the boat's is not one of them. The sister page of
     * such a boat is a "not in our fleet" stub or a 404 (SISTER-1), so no link to it may be offered. Always true unscoped.
     */
    private fun inFleet(site: AiSiteProfile, y: JsonNode): Boolean =
        !site.scoped ||
            (
                y.path("location").path("countryCode").asText("").uppercase() in site.countryCodes &&
                    (site.vesselTypes.isEmpty() || site.vesselTypes.any { it.name == y.path("vesselType").asText("") })
            )

    /** The boat's page on the sister [site] itself, in the visitor's [locale] (English has no prefix); dates are the offer's, when given. */
    private fun sisterUrl(site: AiSiteProfile, locale: String, slug: String, from: String? = null, to: String? = null): String =
        "${site.baseUrl}${site.localePrefix(locale)}${site.boatPath}$slug" + if (from != null && to != null) "?startDate=$from&endDate=$to" else ""

    /** boat4you's booking page for the boat, the only place a sister site's visitor can book; `cc_src` names the site that sent them. */
    private fun bookUrl(site: AiSiteProfile, locale: String, slug: String, from: String? = null, to: String? = null): String {
        val b4y = AiSiteProfile.BOAT4YOU
        val dates = if (from != null && to != null) "startDate=$from&endDate=$to&" else ""
        return "${b4y.baseUrl}${b4y.localePrefix(locale)}${b4y.boatPath}$slug?${dates}currency=EUR&cc_src=${site.siteId}"
    }

    /**
     * `check_boat` (Mario 6.11.2026: the visitor on the Lagoon 46 Alter Ego page said "yes, check" and got a new search
     * instead): whether ONE boat - [input] `boat` (its slug), else the boat page the visitor is on ([page]) - is free for
     * the dates, at what total, as a card. A boat outside a scoped [site]'s fleet, an unknown one or no boat at all is an
     * error the model reads, never a guess.
     */
    fun checkBoat(
        input: JsonNode,
        page: String?,
        site: AiSiteProfile = AiSiteProfile.BOAT4YOU,
        locale: String = "en",
    ): ToolOutcome {
        val asked = input.path("boat").asText("").trim()
        val slug =
            when {
                asked.isNotBlank() -> asked.takeIf { SLUG.matches(it) }
                page.isNullOrBlank() -> null
                else -> Regex(Regex.escape(site.boatPath) + "([A-Za-z0-9-]+)").find(page)?.groupValues?.get(1)?.lowercase()?.takeIf { SLUG.matches(it) }
            } ?: return ToolOutcome("""{"error":"no boat to check - pass the boat's slug from a card or ask the visitor which boat"}""")
        val startDate = input.path("startDate").asText("")
        val endDate = input.path("endDate").asText("")
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull()
        val nights = if (start != null && end != null) ChronoUnit.DAYS.between(start, end).toInt() else 0
        if (start == null || end == null || nights !in MIN_NIGHTS..MAX_NIGHTS) {
            return ToolOutcome("""{"error":"invalid date range — use YYYY-MM-DD, $MIN_NIGHTS to $MAX_NIGHTS nights"}""")
        }
        val today = LocalDate.now(clock)
        if (start.isBefore(today)) return pastDates(start, end, today)
        val boat =
            try {
                client.get().uri(UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts/{slug}").build(slug))
                    .retrieve().body(String::class.java)?.let { objectMapper.readTree(it) }
            } catch (e: Exception) {
                log.warn("chat check_boat lookup failed for '{}': {}", slug, e.message)
                null
            }
        val id = boat?.path("id")?.asLong(0) ?: 0
        if (boat == null || id <= 0) return ToolOutcome("""{"error":"boat not found - ask the visitor which boat they mean or search instead"}""")
        if (!inFleet(site, boat)) return ToolOutcome(objectMapper.writeValueAsString(mapOf("error" to "boat outside this site's fleet", "hint" to fleetHint(site))))
        val label = listOf(boat.path("modelName").asText(""), boat.path("name").asText("")).filter { it.isNotBlank() }.joinToString(" ")
        val body =
            try {
                client.get()
                    .uri(
                        UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts")
                            .queryParam("yid", id).queryParam("startDate", startDate).queryParam("endDate", endDate)
                            .queryParam("currency", "EUR").queryParam("size", 5).queryParam("page", 0)
                            .build().encode().toUri(),
                    )
                    .retrieve().body(String::class.java)
            } catch (e: Exception) {
                log.warn("chat check_boat search failed for '{}': {}", slug, e.message)
                return ToolOutcome("""{"error":"check temporarily unavailable, apologize and suggest trying again"}""")
            }
        val row =
            objectMapper.readTree(body).path("content")
                .firstOrNull { (it.path("id").asLong(0) == id || it.path("slug").asText() == slug) && inFleet(site, it) }
        val offer = row?.let { pricedOffer(it, nights, startDate, endDate) }
        if (row == null || offer == null) {
            val result =
                objectMapper.createObjectNode().apply {
                    put("yacht", label)
                    put("available", false)
                    put("startDate", startDate)
                    put("endDate", endDate)
                    put("base", boat.path("location").path("name").asText(""))
                    val free = freeWeeksNear(slug, start, end)
                    if (free.isNotEmpty()) putArray("freeWeeksNearby").apply { free.forEach { add(it) } }
                    put(
                        "hint",
                        "not bookable for these dates - say so plainly; offer the free weeks nearby if any, and offer to search " +
                            "the same model or a similar size in the same area for these dates (search_yachts with models and orSimilar)",
                    )
                }
            return ToolOutcome(objectMapper.writeValueAsString(result))
        }
        val result =
            objectMapper.createObjectNode().apply {
                put("yacht", label)
                put("available", true)
                put("totalPriceEur", offer.total)
                put("startDate", offer.from)
                put("endDate", offer.to)
                put("nights", offer.nights)
                put("dates", offer.dates)
                putPositive("cabins", row.path("cabins"))
                put("base", row.path("location").path("name").asText("").ifBlank { boat.path("location").path("name").asText("") })
                if (site.scoped) {
                    put("url", sisterUrl(site, locale, slug, offer.from, offer.to))
                    put("bookUrl", bookUrl(site, locale, slug, offer.from, offer.to))
                } else {
                    put("url", "https://www.boat4you.com/boat/$slug?startDate=${offer.from}&endDate=${offer.to}&currency=EUR")
                }
            }
        val card =
            objectMapper.createObjectNode().apply {
                put("name", label)
                put("slug", slug)
                put("imageId", row.path("mainImageId").asLong(0))
                put("totalPriceEur", offer.total)
                put("days", offer.nights)
                putPositive("cabins", row.path("cabins"))
                putPositive("berths", row.path("berths"))
                putPositive("wc", row.path("wc"))
                putPositive("maxPersons", row.path("maxPersons"))
                put("year", row.path("buildYear").asInt(0))
                put("location", result.path("base").asText())
                put("startDate", offer.from)
                put("endDate", offer.to)
                put("dates", offer.dates)
                if (site.scoped) {
                    put("url", sisterUrl(site, locale, slug, offer.from, offer.to))
                    put("bookUrl", bookUrl(site, locale, slug, offer.from, offer.to))
                }
            }
        return ToolOutcome(objectMapper.writeValueAsString(result), objectMapper.createArrayNode().add(card))
    }

    /**
     * The boat's FREE weeks within two weeks of the asked ones (`/standard-offers`, the boat page's calendar), as
     * "from to" ISO dates; empty when there is none or the lookup fails. Dates only: a price comes from check_boat.
     */
    private fun freeWeeksNear(slug: String, start: LocalDate, end: LocalDate): List<String> =
        try {
            client.get()
                .uri(
                    UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts/{slug}/standard-offers")
                        .queryParam("dateFrom", maxOf(start.minusDays(14), LocalDate.now(clock)))
                        .queryParam("dateTo", end.plusDays(14))
                        .queryParam("currency", "EUR")
                        .build(slug),
                )
                .retrieve().body(String::class.java)
                .let { objectMapper.readTree(it) }
                .filter { it.path("status").asText() == "FREE" }
                .mapNotNull { o -> isoDate(o.path("dateFrom"))?.let { from -> isoDate(o.path("dateTo"))?.let { to -> "$from to $to" } } }
                .distinct()
                .sorted()
                .take(4)
        } catch (e: Exception) {
            log.warn("chat free weeks of '{}' failed: {}", slug, e.message)
            emptyList()
        }

    /**
     * Compact facts about the yacht page the visitor is on (widget sends its URL with
     * every message), injected into the system prompt so "this boat" questions work.
     * Pages that are not a boat page of [site] (`/boat/`, or `/catamarans/` and `/yachts/` on a sister site), lookup
     * failures and, on a scoped site, boats outside its fleet return null — the chat just works without page awareness
     * then. A sister site's block links the sister page and, for a boat that can be booked, boat4you's booking page.
     */
    fun pageContext(
        page: String?,
        site: AiSiteProfile = AiSiteProfile.BOAT4YOU,
        locale: String = "en",
    ): String? {
        if (page.isNullOrBlank()) return null
        val slug = Regex(Regex.escape(site.boatPath) + "([A-Za-z0-9-]+)").find(page)?.groupValues?.get(1) ?: return null
        val dates = Regex("startDate=(\\d{4}-\\d{2}-\\d{2}).*?endDate=(\\d{4}-\\d{2}-\\d{2})").find(page)
        val body = try {
            client.get().uri("/public/yachts/$slug").retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat page-context lookup failed for '$slug': ${e.message}")
            return null
        }
        val y = objectMapper.readTree(body)
        val name = y.path("name").asText("")
        if (name.isBlank()) return null
        if (!inFleet(site, y)) return null
        // Brokered by inquiry only: no booking button, no price on the sites (SISTER-18), so the AI must not send them to book.
        val inquiryOnly =
            y.path("inquireOnly").asBoolean(false) || y.path("hasBookableFutureOffer").let { it.isBoolean && !it.asBoolean() }
        val label = listOf(y.path("manufacturerName").asText(""), y.path("modelName").asText(""), name)
            .filter { it.isNotBlank() }.distinct().joinToString(" ")
        val amenities = y.path("amenities")
            .mapNotNull { a -> a.path("equipment").path("labelCode").asText(null) ?: a.path("name").asText(null) }
            .filter { it.isNotBlank() }.distinct().take(25)
        return buildString {
            append("CURRENT PAGE — the visitor is viewing this yacht right now:\n")
            append("- Yacht: $label")
            y.path("buildYear").asInt(0).takeIf { it > 0 }?.let { append(", year $it") }
            y.path("length").asDouble(0.0).takeIf { it > 0 }?.let { append(", length ${it}m") }
            append("\n")
            capacityFacts(y)?.let { append("- $it\n") }
            capacityNotes(y)?.let { append("- $it\n") }
            if (isGulet(y)) append("- Charter: $GULET_CHARTER\n")
            y.path("location").path("name").asText("").takeIf { it.isNotBlank() }
                ?.let { append("- Base: $it\n") }
            if (amenities.isNotEmpty()) append("- Equipment: ${amenities.joinToString(", ")}\n")
            if (dates != null) append("- Dates selected on the page: ${dates.groupValues[1]} to ${dates.groupValues[2]}\n")
            if (inquiryOnly) append("- Booking: on request only - offer the visitor an inquiry, never send them to book online\n")
            if (!site.scoped) {
                append("- Page URL: https://www.boat4you.com/boat/$slug")
            } else {
                append("- Page URL: ${sisterUrl(site, locale, slug)}")
                if (!inquiryOnly) {
                    append("\n- Book online on boat4you: ${bookUrl(site, locale, slug, dates?.groupValues?.get(1), dates?.groupValues?.get(2))}")
                }
            }
        }
    }

    /**
     * "Cabins: 6 (crew cabins: 2), berths: 13 (12 + 1 crew), WC: 6, max people on board: 14 (recommended 10), crew: 1"
     * from the detail JSON's `capacity` block (capacity contract v1, 2.6), unknown parts left out. Berths are berths,
     * never "sleeps up to {maxPersons}"; crew only when a crewed charter is offered. No crew WC: until the corrected
     * NauSys sync (cusma3, nightly) has rewritten a boat, its stored crew WC is still its guest WC, and the chat goes
     * live with cusma2, ahead of that sync - the boat page shows the crew WC once the sync has run.
     */
    internal fun capacityFacts(y: JsonNode): String? {
        val c = y.path("capacity")
        val parts = mutableListOf<String>()
        dimText(c.path("cabins"), y.path("cabins"))?.let { cabins ->
            val crewCabins = positive(c.path("crewCabins"))
            parts += "Cabins: $cabins" + (crewCabins?.let { " (crew cabins: $it)" } ?: "")
        }
        dimText(c.path("berths"), y.path("berths"))?.let { parts += "berths: $it" }
        dimText(c.path("heads"), y.path("wc"))?.let { parts += "WC: $it" }
        positive(c.path("showers"))?.let { parts += "showers: $it" }
        // flat fields only for a payload without the block (an older API answer)
        val max = positive(c.path("maxPersons")) ?: if (!c.isObject) positive(y.path("maxPersons")) else null
        val recommended = positive(c.path("recommendedPersons"))
        when {
            max != null -> parts += "max people on board: $max" + (recommended?.takeIf { it != max }?.let { " (recommended $it)" } ?: "")
            recommended != null -> parts += "recommended people: $recommended"
        }
        val crewed = y.path("charterType").any { it.asText() in CREWED_CHARTER_TYPES }
        val crew = positive(c.path("crewNumber")) ?: if (!c.isObject) positive(y.path("crewNumber")) else null
        if (crewed && crew != null) parts += "crew: $crew"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * The partner's own capacity notes, already sanitized by the API (never the internal remark), in English. Partner
     * text inside the system prompt: only notes of at most [CHAT_NOTE_MAX] characters (every note of the 896-yacht
     * sample is shorter), which keeps the room for text aimed at the model small.
     */
    internal fun capacityNotes(y: JsonNode): String? {
        val c = y.path("capacity")
        val notes =
            listOf("cabins" to "cabins", "berths" to "berths", "heads" to "WC").mapNotNull { (key, label) ->
                c.path(key).path("note").asText("").takeIf { it.isNotBlank() && it.length <= CHAT_NOTE_MAX }?.let { "$label \"$it\"" }
            }
        return notes.takeIf { it.isNotEmpty() }?.let { "Partner capacity notes (verbatim): ${it.joinToString("; ")}" }
    }

    /** "13 (12 + 1 crew)" / "12 (10 in cabins + 2 crew)" / "6", or null when the figure is unknown. */
    private fun dimText(
        dim: JsonNode,
        flat: JsonNode,
    ): String? {
        val value = positive(dim.path("value")) ?: if (dim.isMissingNode) positive(flat) else null
        value ?: return null
        val split = dim.path("split")
        val parts =
            listOfNotNull(
                positive(split.path("guests"))?.toString(),
                positive(split.path("inCabins"))?.let { "$it in cabins" },
                positive(split.path("saloon"))?.let { "$it in the saloon" },
                positive(split.path("crew"))?.let { "$it crew" },
                positive(split.path("skipper"))?.let { "$it skipper" },
            )
        return if (parts.size >= 2) "$value (${parts.joinToString(" + ")})" else value.toString()
    }

    private fun positive(node: JsonNode): Int? = node.takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }

    private fun ObjectNode.putPositive(
        key: String,
        value: JsonNode,
    ) {
        positive(value)?.let { put(key, it) }
    }

    private fun amenityId(labelCode: String): Long? {
        if (amenityIdsByLabel.isEmpty()) {
            amenityIdsByLabel = try {
                client.get().uri("/public/catalogue/all-amenities").retrieve().body(String::class.java)
                    .let { objectMapper.readTree(it) }
                    .mapNotNull { a ->
                        val label = a.path("labelCode").asText("")
                        val id = a.path("id").asLong(0)
                        if (label.isNotBlank() && id > 0) label to id else null
                    }.toMap()
            } catch (e: Exception) {
                log.warn("chat amenity catalogue fetch failed: ${e.message}")
                emptyMap()
            }
        }
        return amenityIdsByLabel[labelCode]
    }

    /**
     * Tool schemas for the Messages API call. [includeBlog] is false on a site without blog guides: the model is then not
     * given `show_blog_posts` at all (deterministic per site, so the tools stay a stable cached prefix).
     */
    fun toolDefinitions(includeBlog: Boolean = true): ArrayNode {
        val tools = objectMapper.createArrayNode()
        tools.add(tool(
            "search_yachts",
            "Search the live boat4you fleet. Requires an ISO-2 country code and a date range " +
                "(charters typically run Saturday to Saturday). Returns real boats with binding " +
                "total prices in EUR for the period; they are also shown to the user as cards. " +
                "The result's searchedArea tells you which area was actually searched — if it " +
                "differs from what the visitor asked, say so honestly. Each boat's startDate/endDate/nights " +
                "are the dates its price is for: when its dates field is \"nearby\", tell the visitor " +
                "the boat is offered for those nearby dates, not the ones they asked for.",
        ) {
            it.putObject("countryCode").put("type", "string")
                .put("description", "ISO-2 country, e.g. HR, GR, IT, FR, ES, TR, BS (Bahamas), SC (Seychelles)")
            it.putObject("location").put("type", "string")
                .put(
                    "description",
                    "City, marina, island or region the visitor named (e.g. Split, Dubrovnik, Athens, " +
                        "Trogir, Lefkas). ALWAYS pass it when they mention a place more specific than a " +
                        "country — results are then limited to that area instead of the whole country. A marina name " +
                        "also searches every marina of that name and the bases nearby.",
                )
            it.putObject("locations").put("type", "array")
                .put(
                    "description",
                    "Instead of location when the visitor names several places or bases (\"out of Lefkada or Preveza\"): " +
                        "ALL of them, up to $MAX_PLACES, in ONE call - never only the first one",
                )
                .putObject("items").put("type", "string")
            it.putObject("models").put("type", "array")
                .put(
                    "description",
                    "Boat models the visitor asked for, manufacturer first (\"Lagoon 46\", \"Bali 4.2\"), up to $MAX_MODELS: " +
                        "only these models are searched (their variants included)",
                )
                .putObject("items").put("type", "string")
            it.putObject("orSimilar").put("type", "boolean")
                .put(
                    "description",
                    "With models, when the visitor says \"or similar\": the asked models AND boats of the same type and length " +
                        "(within $SIMILAR_FT ft), the asked models listed first; each boat's match says which it is",
                )
            it.putObject("lengthFt").put("type", "integer")
                .put(
                    "description",
                    "The size the visitor asks for in feet (\"a 42 ft catamaran\"; metres x 3.28): boats within $SIMILAR_FT ft of it, " +
                        "spread over smaller, the same and bigger sizes and different models",
                )
            it.putObject("minLengthFt").put("type", "integer").put("description", "Optional minimum length in feet")
            it.putObject("maxLengthFt").put("type", "integer").put("description", "Optional maximum length in feet")
            it.putObject("startDate").put("type", "string").put("description", "YYYY-MM-DD; the charter lasts $MIN_NIGHTS to $MAX_NIGHTS nights")
            it.putObject("endDate").put("type", "string").put("description", "YYYY-MM-DD")
            it
                .putObject("windowStart")
                .put("type", "string")
                .put(
                    "description",
                    "YYYY-MM-DD; instead of startDate/endDate when the visitor is flexible: ONE call searches every " +
                        "Saturday-to-Saturday week between windowStart and windowEnd (up to $MAX_WINDOW_WEEKS weeks) and " +
                        "returns each boat at its cheapest week, with matches per week",
                )
            it.putObject("windowEnd").put("type", "string").put("description", "YYYY-MM-DD, the last day of the flexible window")
            it.putObject("vesselType").put("type", "string")
                .put("description", "Optional: SAILING_YACHT, CATAMARAN, MOTOR_YACHT, MOTORBOAT, GULET, MOTORSAILER, POWER_CATAMARAN, LUXURY_MOTOR_YACHT, MINI_CRUISER")
            it.putObject("equipment").put("type", "array")
                .put(
                    "description",
                    "Required equipment, ALL must be present. Use when the visitor asks for it (e.g. air " +
                        "conditioning). Allowed values: air-conditioning, generator, water-maker, wifi, " +
                        "heating, bow-thruster, autopilot, dinghy, bimini, electric-winches, solar-panels, " +
                        "washing-machine, dishwasher, freezer, ice-maker, flybridge, radar, inverter, BBQ",
                )
                .putObject("items").put("type", "string")
            it.putObject("minCabins").put("type", "integer").put("description", "Optional minimum cabins")
            it.putObject("maxCabins").put("type", "integer")
                .put("description", "Optional maximum cabins - when the visitor gives an exact number (\"4 cabins\"), pass it as both minCabins and maxCabins")
            it
                .putObject("minPersons")
                .put("type", "integer")
                .put(
                    "description",
                    "Optional: group size - the boat must take at least this many people (its max. people on board; " +
                        "when the partner gives no maximum, its berths)",
                )
            it.putObject("maxTotalPriceEur").put("type", "integer").put("description", "Optional budget cap, total EUR for the period")
            it.putObject("minTotalPriceEur").put("type", "integer").put("description", "Optional lower price bound, total EUR — e.g. when the visitor wants pricier options than already shown")
            it.putObject("sortByPrice").put("type", "string")
                .put(
                    "description",
                    "asc (default, cheapest first) or desc — USE desc when the visitor asks for " +
                        "luxury, premium or more expensive boats; the result's totalAvailable tells " +
                        "you how many boats match in total beyond the ones returned",
                )
            it.putObject("limit").put("type", "integer").put("description", "Max results, default $DEFAULT_RESULTS, at most $MAX_RESULTS")
        })
        tools.add(tool(
            "check_boat",
            "Check ONE boat for the visitor's dates: whether it can be booked, its binding total in EUR and, when it is taken, " +
                "its free weeks nearby. Use it whenever the visitor asks about a specific boat - the one on the CURRENT PAGE " +
                "(leave boat out) or one from a card (its slug) - and above all when they say \"yes, check it\".",
        ) {
            it.putObject("boat").put("type", "string").put("description", "The boat's slug from a card or url; leave out for the CURRENT PAGE boat")
            it.putObject("startDate").put("type", "string").put("description", "YYYY-MM-DD")
            it.putObject("endDate").put("type", "string").put("description", "YYYY-MM-DD")
        })
        tools.add(tool(
            "request_human_agent",
            "Hand the conversation to a human boat4you agent. Use when the visitor explicitly wants a " +
                "person, has a complaint/payment/legal issue, or you genuinely cannot help. A person from " +
                "the team replies in this chat as soon as they can or contacts the visitor by email or phone.",
        ) {
            it.putObject("reason").put("type", "string").put("description", "Short reason for the handoff")
        })
        tools.add(tool(
            "show_itineraries",
            "Show the visitor this site's page of all our sailing itineraries as a link card under your reply. Use it when " +
                "the visitor asks for a route or itinerary and has not said where they would like to sail, or has no preference.",
        ) { })
        if (includeBlog) {
            tools.add(tool(
                "show_blog_posts",
                "Show the visitor up to 2 boat4you blog guides from the BOAT4YOU GUIDES list as link cards under your " +
                    "reply. Use it whenever a listed guide fits the visitor's question.",
            ) {
                it.putObject("slugs").put("type", "array")
                    .put("description", "Guide slugs exactly as listed in BOAT4YOU GUIDES, at most 2")
                    .putObject("items").put("type", "string")
            })
        }
        tools.add(tool(
            "save_contact",
            "Store the visitor's name, email and/or phone number when they share it, so the team can follow up.",
        ) {
            it.putObject("name").put("type", "string")
            it.putObject("email").put("type", "string")
            it.putObject("phone").put("type", "string")
        })
        return tools
    }

    /**
     * Dates before today (Mario 3.11.2026: in November, "middle to end of June" was searched as the June just gone and a visitor
     * was shown a week that had passed): never searched; the model is told the next time the same dates come.
     */
    private fun pastDates(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): ToolOutcome {
        val years = generateSequence(1L) { it + 1 }.first { !from.plusYears(it).isBefore(today) }
        return ToolOutcome(
            objectMapper.writeValueAsString(
                mapOf(
                    "error" to "these dates are in the past (today is $today) - nothing can be booked for them",
                    "hint" to "a date without a year is the next time it comes: search ${from.plusYears(years)} to ${to.plusYears(years)}, " +
                        "or ask the visitor which year they mean",
                ),
            ),
        )
    }

    private fun tool(name: String, description: String, props: (ObjectNode) -> Unit): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("name", name)
            put("description", description)
            putObject("input_schema").apply {
                put("type", "object")
                props(putObject("properties"))
            }
        }
}
