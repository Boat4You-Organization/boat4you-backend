package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
    @Value("\${chat.self-base-url:http://127.0.0.1:8080}") selfBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: RestClient = RestClient.builder().baseUrl(selfBaseUrl).build()

    // labelCode -> equipment id, lazily fetched from our own cached catalogue endpoint.
    @Volatile
    private var amenityIdsByLabel: Map<String, Long> = emptyMap()

    data class ToolOutcome(val resultForModel: String, val cards: ArrayNode? = null)

    fun searchYachts(input: JsonNode): ToolOutcome {
        val countryCode = input.path("countryCode").asText("").uppercase().take(2)
        val startDate = input.path("startDate").asText("")
        val endDate = input.path("endDate").asText("")
        val vesselType = input.path("vesselType").asText("")
        val location = input.path("location").asText("")
        val minCabins = input.path("minCabins").asInt(0)
        val minPersons = input.path("minPersons").asInt(0)
        val maxTotalPriceEur = input.path("maxTotalPriceEur").asInt(0)
        val minTotalPriceEur = input.path("minTotalPriceEur").asInt(0)
        val sortByPrice = input.path("sortByPrice").asText("asc").let { if (it == "desc") "desc" else "asc" }
        val limit = input.path("limit").asInt(5).coerceIn(1, 6)
        val equipment = input.path("equipment").mapNotNull { it.asText(null) }.filter { it.isNotBlank() }

        if (countryCode.isBlank() || startDate.isBlank() || endDate.isBlank()) {
            return ToolOutcome("""{"error":"countryCode, startDate and endDate are required"}""")
        }
        val days = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)).toInt()
        }.getOrDefault(0)
        if (days !in 1..28) {
            return ToolOutcome("""{"error":"invalid date range — use YYYY-MM-DD, 1 to 28 days"}""")
        }

        // "Split" must search the Split REGION, not all of Croatia (Mario 20.7.2026:
        // bot answered a Split request with boats from Punat). Resolve the place via
        // the same autocomplete the website's "Where" field uses and pass its did.
        val resolved = if (location.isBlank()) null else resolveDestination(location, countryCode)
        val equipmentIds = equipment.mapNotNull { amenityId(it) }

        val uri = UriComponentsBuilder.fromPath("/public/yachts")
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
            .build().toUriString()

        val body = try {
            client.get().uri(uri).retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat search_yachts failed: ${e.message}")
            return ToolOutcome("""{"error":"search temporarily unavailable, apologize and suggest trying again"}""")
        }

        val parsed = objectMapper.readTree(body)
        val content = parsed.path("content")
        val totalAvailable = parsed.path("page").path("totalElements").asInt(content.size())
        val forModel = objectMapper.createArrayNode()
        val cards = objectMapper.createArrayNode()
        var taken = 0
        for (y in content) {
            if (taken >= limit) break
            // Headline convention: prices are shown as the TOTAL for the
            // period (per-day clientPriceEur x days), never per-day.
            val perDay = y.path("clientPriceEur").decimalValue()
            if (perDay <= BigDecimal.ZERO) continue
            val total = perDay.multiply(BigDecimal(days)).setScale(0, RoundingMode.HALF_UP).toInt()
            if (maxTotalPriceEur in 1 until total) continue
            if (minTotalPriceEur > 0 && total < minTotalPriceEur) continue

            val slug = y.path("slug").asText()
            val label = listOf(y.path("modelName").asText(""), y.path("name").asText(""))
                .filter { it.isNotBlank() }.joinToString(" ")

            forModel.add(
                objectMapper.createObjectNode().apply {
                    put("yacht", label)
                    put("totalPriceEur", total)
                    put("cabins", y.path("cabins").asInt(0))
                    put("maxPersons", y.path("maxPersons").asInt(0))
                    put("year", y.path("buildYear").asInt(0))
                    put("base", y.path("location").path("name").asText(""))
                    val amenities = y.path("amenityKeys").mapNotNull { it.asText(null) }
                    if (amenities.isNotEmpty()) put("topAmenities", amenities.joinToString(","))
                    put("url", "https://www.boat4you.com/boat/$slug?startDate=$startDate&endDate=$endDate&currency=EUR")
                },
            )
            cards.add(
                objectMapper.createObjectNode().apply {
                    put("name", label)
                    put("slug", slug)
                    put("imageId", y.path("mainImageId").asLong(0))
                    put("totalPriceEur", total)
                    put("days", days)
                    put("cabins", y.path("cabins").asInt(0))
                    put("maxPersons", y.path("maxPersons").asInt(0))
                    put("year", y.path("buildYear").asInt(0))
                    put("location", y.path("location").path("name").asText(""))
                    put("startDate", startDate)
                    put("endDate", endDate)
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
        return ToolOutcome(objectMapper.writeValueAsString(result), if (taken > 0) cards else null)
    }

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
                    UriComponentsBuilder.fromPath("/public/locations")
                        .queryParam("name", query).queryParam("size", 20).build().toUriString(),
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
                "differs from what the visitor asked, say so honestly.",
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
            it.putObject("startDate").put("type", "string").put("description", "YYYY-MM-DD")
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
            it.putObject("minPersons").put("type", "integer").put("description", "Optional: group size — boat must sleep at least this many")
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
