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

    data class ToolOutcome(val resultForModel: String, val cards: ArrayNode? = null)

    fun searchYachts(input: JsonNode): ToolOutcome {
        val countryCode = input.path("countryCode").asText("").uppercase().take(2)
        val startDate = input.path("startDate").asText("")
        val endDate = input.path("endDate").asText("")
        val vesselType = input.path("vesselType").asText("")
        val minCabins = input.path("minCabins").asInt(0)
        val maxTotalPriceEur = input.path("maxTotalPriceEur").asInt(0)
        val limit = input.path("limit").asInt(5).coerceIn(1, 6)

        if (countryCode.isBlank() || startDate.isBlank() || endDate.isBlank()) {
            return ToolOutcome("""{"error":"countryCode, startDate and endDate are required"}""")
        }
        val days = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)).toInt()
        }.getOrDefault(0)
        if (days !in 1..28) {
            return ToolOutcome("""{"error":"invalid date range — use YYYY-MM-DD, 1 to 28 days"}""")
        }

        val uri = UriComponentsBuilder.fromPath("/public/yachts")
            .queryParam("countryCodes", countryCode)
            .queryParam("startDate", startDate)
            .queryParam("endDate", endDate)
            .queryParam("currency", "EUR")
            .queryParam("size", 20)
            .queryParam("page", 0)
            .queryParam("sortBy", "asc")
            .apply {
                if (vesselType.isNotBlank()) queryParam("vesselType", vesselType)
                if (minCabins > 0) queryParam("minCabins", minCabins)
            }
            .build().toUriString()

        val body = try {
            client.get().uri(uri).retrieve().body(String::class.java)
        } catch (e: Exception) {
            log.warn("chat search_yachts failed: ${e.message}")
            return ToolOutcome("""{"error":"search temporarily unavailable, apologize and suggest trying again"}""")
        }

        val content = objectMapper.readTree(body).path("content")
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
            set<JsonNode>("yachts", forModel)
            if (taken == 0) put("hint", "no results — suggest relaxing filters (dates, budget, cabins) or another country")
        }
        return ToolOutcome(objectMapper.writeValueAsString(result), if (taken > 0) cards else null)
    }

    /** Tool schemas for the Messages API call. */
    fun toolDefinitions(): ArrayNode {
        val tools = objectMapper.createArrayNode()
        tools.add(tool(
            "search_yachts",
            "Search the live boat4you fleet. Requires an ISO-2 country code and a date range " +
                "(charters typically run Saturday to Saturday). Returns real boats with binding " +
                "total prices in EUR for the period; they are also shown to the user as cards.",
        ) {
            it.putObject("countryCode").put("type", "string")
                .put("description", "ISO-2 country, e.g. HR, GR, IT, FR, ES, TR, BS (Bahamas), SC (Seychelles)")
            it.putObject("startDate").put("type", "string").put("description", "YYYY-MM-DD")
            it.putObject("endDate").put("type", "string").put("description", "YYYY-MM-DD")
            it.putObject("vesselType").put("type", "string")
                .put("description", "Optional: SAILING_YACHT, CATAMARAN, MOTOR_YACHT, MOTORBOAT, GULET, MOTORSAILER, POWER_CATAMARAN, LUXURY_MOTOR_YACHT, MINI_CRUISER")
            it.putObject("minCabins").put("type", "integer").put("description", "Optional minimum cabins")
            it.putObject("maxTotalPriceEur").put("type", "integer").put("description", "Optional budget cap, total EUR for the period")
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
