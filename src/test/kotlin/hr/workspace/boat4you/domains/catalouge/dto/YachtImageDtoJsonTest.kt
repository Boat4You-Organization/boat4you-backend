package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.services.toDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * YachtImageDto's new fields (kind, caption, credit, sourceUrl - V9_89 gallery) are additive and left out while null,
 * so every answer that does not set them (the boat page with GALLERY_EXTENDED=OFF, my reservation, the custom-boat
 * admin answer) keeps its JSON byte for byte, and a client that reads only id / position / mainImage / layout is unaffected.
 */
class YachtImageDtoJsonTest {
    private val json: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    @Test
    fun `a partner image without the new fields serializes as before`() {
        val image =
            YachtImage().apply {
                id = 7L
                url = "y-1/a.webp"
                externalUrl = "https://partner.example/deck_plan.png"
                position = 2
            }

        json.writeValueAsString(image.toDto()) shouldBe """{"id":7,"position":2,"mainImage":null,"layout":true}"""
        json.writeValueAsString(YachtImageDto(id = 8L, position = 0, mainImage = true)) shouldBe
            """{"id":8,"position":0,"mainImage":true,"layout":false}"""
    }

    @Test
    fun `the new fields appear only when set, after the old ones`() {
        json.writeValueAsString(YachtImageDto(id = 9L, position = 1000, mainImage = true, kind = YachtImageDto.KIND_MODEL_SAMPLE)) shouldBe
            """{"id":9,"position":1000,"mainImage":true,"layout":false,"kind":"MODEL_SAMPLE"}"""
    }

    @Test
    fun `an old answer still reads into the DTO, the new fields null`() {
        json.readValue("""{"id":7,"position":2,"mainImage":null,"layout":true}""", YachtImageDto::class.java) shouldBe
            YachtImageDto(id = 7L, position = 2, mainImage = null, layout = true)
    }
}
