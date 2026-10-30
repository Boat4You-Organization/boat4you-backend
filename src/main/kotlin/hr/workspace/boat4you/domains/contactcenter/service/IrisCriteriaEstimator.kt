package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale

/**
 * Recovers an approximate [SearchCriteria] for an IRIS search made before the live recording existed, from the two things
 * that were stored: the yacht cards of the reply and the visitor's message before it (FACTS-4: only cards survive, the
 * model's tool call does not). Only what can be read honestly is filled in: the dates and the number of boats come from
 * the cards, the country, vessel type, persons, cabins and budget from the visitor's own words. A card's location is the
 * boat's base, not the asked destination, so `location`, `did`, `searchedArea`, `totalAvailable` and `minPriceEur` stay
 * null and `equipment` empty.
 */
object IrisCriteriaEstimator {
    private val PERSONS =
        Regex(
            """(?<!\d)(\d{1,2})\s*(?:people|persons?|pax|guests|adults|osob[ae]?|ljudi|personen|personnes|persone|personas|pessoas|osób|osoby)""",
        )
    private val CABINS = Regex("""(?<!\d)(\d{1,2})\s*(?:cabins?|kabin[ae]?|kabinen|cabines|cabine|camarotes?|kajut[y]?)""")
    private val BUDGET =
        Regex(
            """(?:€|eur(?:o|a)?)\s*(\d{1,3}(?:[.,\s]\d{3})+|\d{3,7})|(\d{1,3}(?:[.,\s]\d{3})+|\d{3,7})\s*(?:€|eur(?:o|a)?\b)""",
        )

    /** First match in this order. */
    private val COUNTRIES =
        listOf(
            "HR" to Regex("croatia|hrvatsk|kroatien|croatie|croazia|croacia|croácia|chorwacj|kroati"),
            "GR" to Regex("greece|grčk|grck|griechenland|grèce|grece|grecia|grécia|grecj|griekenland"),
            "IT" to Regex("italy|italij|italien|italie|italia|itália|włoch|wloch"),
            "ES" to Regex("spain|španjolsk|spanjolsk|spanien|espagne|spagna|españa|espana|espanha|hiszpani|spanje"),
            "TR" to Regex("turkey|tursk|türkei|turkei|turquie|turchia|turquía|turquia|turcj|turkije"),
            "ME" to Regex("montenegro|crna gora|crnoj gori|czarnogór"),
        )

    /** First match in this order. */
    private val VESSELS =
        listOf(
            "POWER_CATAMARAN" to Regex("power ?catamaran|motorni katamaran"),
            "CATAMARAN" to Regex("catamar|katamar"),
            "GULET" to Regex("gulet"),
            "MOTOR_YACHT" to Regex("motor ?yacht|motorna jahta|motoryacht|yacht à moteur"),
            "SAILING_YACHT" to Regex("sailing ?yacht|sailboat|jedrilic|segelyacht|segelboot|voilier|barca a vela|velero|veleiro"),
        )

    /** Null unless [payloadJson] holds a non-empty `yachts` array. [visitorText] is the visitor's message the reply answered, if known. */
    fun estimate(
        payloadJson: String?,
        visitorText: String?,
        mapper: ObjectMapper,
    ): SearchCriteria? {
        val cards = cardsOf(payloadJson, mapper) ?: return null
        // Cards from before 14 October carry the asked dates and have no `dates` key; later ones say "exact" or "nearby".
        val asked = cards.firstOrNull { it.path("dates").let { d -> d.isMissingNode || (d.isTextual && d.textValue() == "exact") } }
        val text = visitorText?.lowercase(Locale.ROOT).orEmpty()
        return SearchCriteria(
            countryCode = COUNTRIES.firstOrNull { it.second.containsMatchIn(text) }?.first,
            location = null,
            did = null,
            startDate = asked?.let { SearchCriteria.parseDate(it.path("startDate").textValue()) },
            endDate = asked?.let { SearchCriteria.parseDate(it.path("endDate").textValue()) },
            minPersons = number(PERSONS, text, 1..50),
            minCabins = number(CABINS, text, 1..12),
            minPriceEur = null,
            maxPriceEur = number(BUDGET, text, 100..1_000_000),
            vesselType = VESSELS.firstOrNull { it.second.containsMatchIn(text) }?.first,
            equipment = emptyList(),
            resultCount = cards.size,
            totalAvailable = null,
            searchedArea = null,
        )
    }

    private fun cardsOf(
        payloadJson: String?,
        mapper: ObjectMapper,
    ): List<JsonNode>? {
        if (payloadJson.isNullOrBlank()) return null
        val yachts =
            try {
                mapper.readTree(payloadJson)?.path("yachts")
            } catch (_: JsonProcessingException) {
                null
            }
        return yachts?.takeIf { it.isArray && it.size() > 0 }?.toList()
    }

    /** The digits of the first match of [pattern], or null when there is none or the number lies outside [range]. */
    private fun number(
        pattern: Regex,
        text: String,
        range: IntRange,
    ): Int? {
        val groups = pattern.find(text)?.groupValues ?: return null
        return groups
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
            ?.takeIf { it in range }
    }
}
