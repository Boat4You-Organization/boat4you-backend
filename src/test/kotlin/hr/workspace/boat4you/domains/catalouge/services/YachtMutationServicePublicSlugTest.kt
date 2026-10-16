package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtRequest
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetail
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.Optional

/**
 * Owner rule 16.10.2026 for the admin's custom yacht: the slug it is created with (after the INSERT gave it its id) is
 * frozen once; an edit - even a rename - never freezes again. Repositories are answer-by-method-name mocks.
 */
class YachtMutationServicePublicSlugTest {
    private val lagoon =
        Model().apply {
            id = 10L
            name = "Lagoon 42"
            manufacturer = Manufacturer().also { it.name = "Lagoon" }
        }
    private val saved = mutableMapOf<Long, Yacht>()
    private var nextId = 7001L
    private val frozen = mutableListOf<Pair<Long, String>>()

    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                // IDENTITY: the INSERT gives a new boat its id
                "save", "saveAndFlush" -> inv.getArgument<Yacht>(0).also { if (it.id == null) it.id = nextId++ }.also { saved[it.id!!] = it }
                "findById" -> Optional.ofNullable(saved[inv.arguments[0]])
                "freezePublicSlug" -> null.also { frozen += inv.getArgument<Long>(0) to inv.getArgument<String>(1) }
                else -> null
            }
        }
    private val models =
        mock(ModelRepository::class.java) { inv -> if (inv.method.name == "getReferenceById") lagoon else null }
    private val details =
        mock(CustomYachtDetailRepository::class.java) { inv ->
            when (inv.method.name) {
                "findByYachtId" -> CustomYachtDetail()
                "save" -> inv.getArgument(0)
                else -> null
            }
        }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        YachtMutationService(
            yachtRepository = yachts,
            locationRepository = any(),
            customYachtDetailRepository = details,
            languageRepository = any(),
            yachtTranslationRepository = any(),
            modelRepository = models,
            yachtImageRepository = any(),
            fileSystemService = any(),
            yachtQueryingService = any(),
            equipmentRepository = any(),
            equipmentAliases = any(),
            manufacturerRepository = any(),
            countryRepository = any(),
        )

    private fun request(name: String) =
        CustomYachtRequest(name = name, modelId = lagoon.id, countryId = "c-1", locationId = "l-40", lowPrice = BigDecimal.TEN)

    @Test
    fun `a custom yacht is frozen once at creation, an edit never freezes again`() {
        service.createYacht(request("Masterpiece"), null, null, null)

        frozen shouldContainExactly listOf(7001L to "lagoon-42-masterpiece-7001")

        service.updateYacht(7001L, request("Renamed"))

        saved[7001L]!!.name shouldBe "Renamed"
        frozen shouldContainExactly listOf(7001L to "lagoon-42-masterpiece-7001")
    }
}
