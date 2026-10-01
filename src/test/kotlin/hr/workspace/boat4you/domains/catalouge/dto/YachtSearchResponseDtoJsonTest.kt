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
}
