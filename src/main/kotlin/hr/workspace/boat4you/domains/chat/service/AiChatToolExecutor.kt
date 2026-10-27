package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    }

    /** A gulet by the API's vesselType / modelName (GuletRules), for the search rows and the boat page alike. */
    internal fun isGulet(y: JsonNode): Boolean =
        GuletRules.isGulet(
            VesselType.entries.firstOrNull { it.name == y.path("vesselType").asText("") },
            y.path("modelName").asText(null),
        )

    fun searchYachts(input: JsonNode): ToolOutcome {
        val countryCode = input.path("countryCode").asText("").trim().uppercase().take(2).takeIf { COUNTRY_CODE.matches(it) } ?: ""
        val startDate = input.path("startDate").asText("")
        val endDate = input.path("endDate").asText("")
        val vesselType = input.path("vesselType").asText("").trim().uppercase().takeIf { it in VESSEL_TYPES } ?: ""
        val location = input.path("location").asText("").trim().takeIf { LOCATION.matches(it) } ?: ""
        val minCabins = input.path("minCabins").asInt(0)
        val minPersons = input.path("minPersons").asInt(0)
        val maxTotalPriceEur = input.path("maxTotalPriceEur").asInt(0)
        val minTotalPriceEur = input.path("minTotalPriceEur").asInt(0)
        val sortByPrice = input.path("sortByPrice").asText("asc").let { if (it == "desc") "desc" else "asc" }
        val limit = input.path("limit").asInt(5).coerceIn(1, 6)
        val equipment = input.path("equipment").mapNotNull { it.asText(null) }.filter { it.isNotBlank() }

        // The whitelisted values this call works with, for the contact center's search log; blank or <= 0 means "not set".
        fun criteria(
            resolved: ResolvedPlace?,
            sentEquipment: List<String>,
            resultCount: Int,
            totalAvailable: Int?,
        ) = SearchCriteria(
            countryCode = countryCode.takeIf { it.isNotBlank() },
            location = location.takeIf { it.isNotBlank() },
            did = resolved?.did?.toLongOrNull(),
            startDate = SearchCriteria.parseDate(startDate),
            endDate = SearchCriteria.parseDate(endDate),
            minPersons = minPersons.takeIf { it > 0 },
            minCabins = minCabins.takeIf { it > 0 },
            minPriceEur = minTotalPriceEur.takeIf { it > 0 },
            maxPriceEur = maxTotalPriceEur.takeIf { it > 0 },
            vesselType = vesselType.takeIf { it.isNotBlank() },
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

        // "Split" must search the Split REGION, not all of Croatia (Mario 20.7.2026:
        // bot answered a Split request with boats from Punat). Resolve the place via
        // the same autocomplete the website's "Where" field uses and pass its did.
        val resolved = if (location.isBlank()) null else resolveDestination(location, countryCode)
        val equipmentIds = equipment.mapNotNull { amenityId(it) }
        val sentEquipment = equipment.filter { amenityId(it) != null }

        val uri = UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/yachts")
            .queryParam("startDate", startDate)
            .queryParam("endDate", endDate)
            .queryParam("currency", "EUR")
            .queryParam("size", 50)
            .queryParam("page", 0)
            .queryParam("sortBy", sortByPrice)
            .apply {
                if (resolved != null) queryParam("did", resolved.did) else queryParam("countryCodes", countryCode)
                if (vesselType.isNotBlank()) queryParam("vesselType", vesselType)
                if (minCabins > 0) queryParam("minCabins", minCabins)
                if (minPersons > 0) queryParam("minPersons", minPersons)
                if (equipmentIds.isNotEmpty()) queryParam("amenities", equipmentIds)
            }
            .build().encode().toUri()

        val body = try {
            client.get().uri(uri).retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat search_yachts failed: ${e.message}")
            return ToolOutcome(
                """{"error":"search temporarily unavailable, apologize and suggest trying again"}""",
                search = criteria(resolved, sentEquipment, 0, null),
            )
        }

        val parsed = objectMapper.readTree(body)
        val content = parsed.path("content")
        val totalAvailable = parsed.path("page").path("totalElements").asInt(content.size())
        val forModel = objectMapper.createArrayNode()
        val cards = objectMapper.createArrayNode()
        var taken = 0
        for (y in content) {
            if (taken >= limit) break
            val offer = pricedOffer(y, days, startDate, endDate) ?: continue
            val total = offer.total
            if (maxTotalPriceEur in 1 until total) continue
            if (minTotalPriceEur > 0 && total < minTotalPriceEur) continue
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
                    put("url", "https://www.boat4you.com/boat/$slug?startDate=${offer.from}&endDate=${offer.to}&currency=EUR")
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
                resolved != null -> put("searchedArea", "${resolved.name} (${resolved.type.lowercase()})")
                location.isNotBlank() ->
                    put("searchedArea", "place '$location' not found — searched the whole country; tell the visitor honestly")
            }
            if (equipment.isNotEmpty() && equipmentIds.size < equipment.size) {
                put("equipmentNote", "some requested equipment filters are unknown and were ignored: " +
                    equipment.filter { amenityId(it) == null }.joinToString(","))
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

    private data class ResolvedPlace(val did: String, val name: String, val type: String)

    /**
     * Same lookup the website's "Where" autocomplete uses. Picks the best row for the
     * bot: prefer a REGION (server-side it expands to all its marinas), then a MARINA,
     * then a COUNTRY; rows from another country than the one the visitor asked for are
     * skipped so "Split" never resolves outside HR when countryCode=HR.
     */
    private fun resolveDestination(query: String, countryCode: String): ResolvedPlace? {
        val body = try {
            client.get()
                .uri(
                    UriComponentsBuilder.fromUriString(selfBaseUrl).path("/public/locations")
                        .queryParam("name", query).queryParam("size", 20).build().encode().toUri(),
                )
                .retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat location resolve failed for '$query': ${e.message}")
            return null
        }
        val rows = objectMapper.readTree(body).path("content")
            .filter { r ->
                val cc = r.path("countryCode").asText("")
                cc.isBlank() || countryCode.isBlank() || cc.equals(countryCode, ignoreCase = true)
            }
        val best = rows.minByOrNull {
            when (it.path("locationType").asText()) {
                "REGION" -> 0
                "MARINA" -> 1
                else -> 2
            }
        } ?: return null
        return ResolvedPlace(
            did = best.path("id").asText(),
            name = best.path("name").asText(),
            type = best.path("locationType").asText(),
        )
    }

    /**
     * Compact facts about the yacht page the visitor is on (widget sends its URL with
     * every message), injected into the system prompt so "this boat" questions work.
     * Non-/boat/ pages and lookup failures return null — the chat just works without
     * page awareness then.
     */
    fun pageContext(page: String?): String? {
        if (page.isNullOrBlank()) return null
        val slug = Regex("/boat/([A-Za-z0-9-]+)").find(page)?.groupValues?.get(1) ?: return null
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
            append("- Page URL: https://www.boat4you.com/boat/$slug")
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

    /** Tool schemas for the Messages API call. */
    fun toolDefinitions(): ArrayNode {
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
                        "country — results are then limited to that area instead of the whole country.",
                )
            it.putObject("startDate").put("type", "string").put("description", "YYYY-MM-DD; the charter lasts $MIN_NIGHTS to $MAX_NIGHTS nights")
            it.putObject("endDate").put("type", "string").put("description", "YYYY-MM-DD")
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
            it.putObject("limit").put("type", "integer").put("description", "Max results, default 5")
        })
        tools.add(tool(
            "request_human_agent",
            "Hand the conversation to a human boat4you agent. Use when the visitor explicitly wants a " +
                "person, has a complaint/payment/legal issue, or you genuinely cannot help. The team " +
                "replies in this same chat.",
        ) {
            it.putObject("reason").put("type", "string").put("description", "Short reason for the handoff")
        })
        tools.add(tool(
            "show_blog_posts",
            "Show the visitor up to 2 boat4you blog guides from the BOAT4YOU GUIDES list as link cards under your " +
                "reply. Use it whenever a listed guide fits the visitor's question.",
        ) {
            it.putObject("slugs").put("type", "array")
                .put("description", "Guide slugs exactly as listed in BOAT4YOU GUIDES, at most 2")
                .putObject("items").put("type", "string")
        })
        tools.add(tool(
            "save_contact",
            "Store the visitor's name and/or email when they share it, so the team can follow up.",
        ) {
            it.putObject("name").put("type", "string")
            it.putObject("email").put("type", "string")
        })
        return tools
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
