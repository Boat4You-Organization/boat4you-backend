package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

/**
 * The sitemaps of all 7 sites parse `updatedAt` from `GET /public/yachts` into `<lastmod>` (Codex audit N7, review
 * 1.10.2026): the wire format is part of the contract, not only the mapper argument. The API serialises with Spring
 * Boot's auto-configured ObjectMapper (no custom mapper bean, no spring.jackson settings), so this test uses exactly
 * that mapper: an ISO-8601 UTC string in whole seconds, and an explicit null when no change is recorded.
 */
class YachtSearchResponseDtoJsonTest {
    // Unit on purpose: JUnit runs only void test methods, and run() returns the runner.
    private fun bootMapper(assertions: (ObjectMapper) -> Unit) {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .run { context -> assertions(context.getBean(ObjectMapper::class.java)) }
    }

    @Test
    fun `updatedAt is an ISO-8601 UTC string in whole seconds`() =
        bootMapper { mapper ->
            val json =
                mapper.writeValueAsString(
                    YachtSearchResponseDto(id = 3528, slug = "beneteau-oceanis-461-ilia-3528", name = "Ilia", updatedAt = Instant.parse("2026-10-02T06:12:41Z")),
                )
            json shouldContain "\"updatedAt\":\"2026-10-02T06:12:41Z\""
            mapper.readTree(json).get("updatedAt").isTextual shouldBe true
        }

    @Test
    fun `no recorded change is an explicit null`() =
        bootMapper { mapper ->
            val json = mapper.writeValueAsString(YachtSearchResponseDto(id = 3528, slug = "beneteau-oceanis-461-ilia-3528", name = "Ilia"))
            mapper.readTree(json).get("updatedAt").isNull shouldBe true
        }

    /**
     * Capacity contract v1 (2.2 / 2.7): the frontends read `berths`, `wc` and the `capacity` block by these names, and
     * the sisters' withoutPartnerIds drops any key matching /agency|external|partner|company|source|mmk|nausys|operator/i.
     */
    @Test
    fun `capacity block wire format, broker notes null for the public`() =
        bootMapper { mapper ->
            val capacity =
                CapacityDto(
                    cabins = CapacityDimDto(value = 4, note = "4 +2", split = null),
                    berths = CapacityDimDto(value = 13, note = null, split = CapacitySplitDto(guests = 12, inCabins = null, saloon = null, crew = 1, skipper = null)),
                    heads = null,
                    crewCabins = null,
                    crewHeads = null,
                    showers = null,
                    crewShowers = null,
                    maxPersons = 14,
                    recommendedPersons = null,
                    crewNumber = 1,
                )
            val json =
                mapper.writeValueAsString(
                    YachtSearchResponseDto(id = 8351, slug = "aura-51-dione-ii-8351", name = "Dione II", berths = 13, wc = 6, capacity = capacity),
                )
            val tree = mapper.readTree(json)
            tree.get("berths").asInt() shouldBe 13
            tree.get("wc").asInt() shouldBe 6
            tree.get("brokerNotes").isNull shouldBe true
            mapper.writeValueAsString(tree.get("capacity")) shouldBe
                "{\"cabins\":{\"value\":4,\"note\":\"4 +2\",\"split\":null}," +
                "\"berths\":{\"value\":13,\"note\":null,\"split\":{\"guests\":12,\"inCabins\":null,\"saloon\":null,\"crew\":1,\"skipper\":null}}," +
                "\"heads\":null,\"crewCabins\":null,\"crewHeads\":null,\"showers\":null,\"crewShowers\":null,\"maxPersons\":14," +
                "\"recommendedPersons\":null,\"crewNumber\":1}"
            val partnerIdKey = Regex("agency|external|partner|company|source|mmk|nausys|operator", RegexOption.IGNORE_CASE)
            listOf("berths", "wc", "capacity", "brokerNotes").forEach { partnerIdKey.containsMatchIn(it) shouldBe false }
        }

    /** The admin Offers pill (9.10.2026): absent from a public answer - not even a null key - and named as the admin reads it. */
    @Test
    fun `offerCharter is left out when null and wire-named for admins`() =
        bootMapper { mapper ->
            val public = mapper.readTree(mapper.writeValueAsString(YachtSearchResponseDto(id = 18886, slug = "gulet-sylvia-r-18886", name = "Sylvia R")))
            public.has("offerCharter") shouldBe false
            val admin =
                mapper.writeValueAsString(
                    YachtSearchResponseDto(
                        id = 3528,
                        slug = "beneteau-oceanis-461-ilia-3528",
                        name = "Ilia",
                        offerCharter = OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "Skipper + food"),
                    ),
                )
            admin shouldContain "\"offerCharter\":{\"kind\":\"SKIPPERED\",\"basis\":\"OBLIGATORY_SKIPPER\",\"obligatoryExtra\":\"Skipper + food\"}"
        }
}
