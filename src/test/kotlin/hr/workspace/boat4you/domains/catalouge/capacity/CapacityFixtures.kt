package hr.workspace.boat4you.domains.catalouge.capacity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.io.ClassPathResource
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Capacity contract v1 fixtures (src/test/resources/capacity): the expectation tables reviewed with the contract
 * (parser.json, engine_power.json), the 7 reference boats (trimmed partner payloads + expected detail / search blocks)
 * and the distinct partner texts of the 896-yacht MMK sample. Names: the real operators.txt resource plus the agency
 * names of the V1_08 / V1_09 seed migrations (1,559 rows), as the contract verified them.
 */
object CapacityFixtures {
    /** The API's own ObjectMapper configuration (Spring Boot defaults, Kotlin module). */
    val mapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    fun json(name: String): JsonNode = mapper.readTree(ClassPathResource("capacity/$name").inputStream)

    val agencySeedNames: List<String> by lazy {
        copyRows("V1_08__insert_nausys_agencies.sql", 0) + copyRows("V1_09__insert_mmk_agencies.sql", 2)
    }

    val operators: Pair<List<String>, List<String>> by lazy {
        PartnerNameMatcher.parseOperators(ClassPathResource(PartnerTextSanitizer.OPERATORS_RESOURCE).inputStream.bufferedReader().readText())
    }

    val nameMatcher: PartnerNameMatcher by lazy { PartnerNameMatcher.of(operators.first, operators.second, agencySeedNames) }

    /** The production sanitizer over operators.txt + the seed agency names. */
    fun sanitizer(agencyNames: List<String> = agencySeedNames): PartnerTextSanitizer {
        val agencies = mock(AgencyRepository::class.java)
        `when`(agencies.findAllNames()).thenReturn(agencyNames)
        return PartnerTextSanitizer(agencies)
    }

    /** Name column of a `COPY ... FROM STDIN` seed block (the contract test's copyRows). */
    private fun copyRows(
        file: String,
        nameCol: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        var on = false
        ClassPathResource("db/migration/$file").inputStream.bufferedReader().readLines().forEach { line ->
            when {
                line.contains("FROM STDIN", ignoreCase = true) -> on = true
                on && line.trim() == "\\." -> on = false
                on && line.isNotBlank() ->
                    line.split(';').getOrNull(nameCol)?.removePrefix("\"")?.removeSuffix("\"")?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }
}
