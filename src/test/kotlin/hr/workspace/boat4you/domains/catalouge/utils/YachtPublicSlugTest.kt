package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * Owner rule 16.10.2026: a boat's public URL slug never changes again. The frozen yacht.public_slug (V9_90) wins over
 * anything the name or model says now; while it is NULL the slug is computed exactly as before (byte for byte).
 */
class YachtPublicSlugTest {
    private fun yacht(
        name: String,
        frozen: String? = null,
    ) = Yacht().apply {
        id = 9946
        this.name = name
        publicSlug = frozen
        model = Model().apply {
            this.name = "Leopard 42"
            manufacturer = Manufacturer().also { it.name = "Leopard Catamarans" }
        }
    }

    @Test
    fun `not frozen - today's computed slug, byte for byte`() {
        fun same(
            manufacturer: String?,
            model: String?,
            name: String?,
            id: Long,
        ) = YachtPublicSlug.of(null, manufacturer, model, name, id) shouldBe SlugUtils.toSlugWithId(manufacturer, model, name, id)
        same("Lagoon", "Lagoon 42", "My Lagoon", 5)
        same(null, "Gulet", "Gulet", 18992)
        same("Lagoon", "Lagoon 42", "Lagoon 42", 5)
        same(null, null, null, 1)
        YachtPublicSlug.of(yacht("Boat4You 424/4/4 Premium")) shouldBe "leopard-catamarans-leopard-42-boat4you-42444-premium-9946"
    }

    @Test
    fun `frozen - a rename, a model merge or a naming rule never moves the URL`() {
        val boat = yacht("Sunsail 424/4/4 Premium", frozen = "leopard-catamarans-leopard-42-sunsail-42444-premium-9946")
        YachtPublicSlug.of(boat) shouldBe "leopard-catamarans-leopard-42-sunsail-42444-premium-9946"

        boat.name = "Boat4You 424/4/4 Premium"
        boat.model = Model().apply { name = "Leopard 42 (2021)" }
        YachtPublicSlug.of(boat) shouldBe "leopard-catamarans-leopard-42-sunsail-42444-premium-9946"
        YachtPublicSlug.of("gulet-18992", null, "Gulet", "Gulet II", 18992) shouldBe "gulet-18992"
    }

    @Test
    fun `a stored slug that does not end in the boat's own id is ignored - the sites resolve by the trailing id`() {
        YachtPublicSlug.of("lagoon-42-my-lagoon-6", "Lagoon", "Lagoon 42", "My Lagoon", 5) shouldBe "lagoon-42-my-lagoon-5"
        YachtPublicSlug.of("lagoon-42-my-lagoon", "Lagoon", "Lagoon 42", "My Lagoon", 5) shouldBe "lagoon-42-my-lagoon-5"
        YachtPublicSlug.of("", "Lagoon", "Lagoon 42", "My Lagoon", 5) shouldBe "lagoon-42-my-lagoon-5"
    }

    @Test
    fun `creation freezes the slug of the public name, once`() {
        val repository = mock(YachtRepository::class.java)
        val boat = yacht("Boat4You 424/4/4 Premium")

        YachtPublicSlug.freezeOnCreate(boat, repository)

        verify(repository).freezePublicSlug(9946, "leopard-catamarans-leopard-42-boat4you-42444-premium-9946")
        boat.publicSlug shouldBe "leopard-catamarans-leopard-42-boat4you-42444-premium-9946"

        // the repository writes only a NULL slug; in memory a slug already there is kept as well
        val frozen = yacht("Renamed", frozen = "leopard-catamarans-leopard-42-old-9946")
        YachtPublicSlug.freezeOnCreate(frozen, repository)
        frozen.publicSlug shouldBe "leopard-catamarans-leopard-42-old-9946"
    }

    @Test
    fun `list pages look the frozen slugs up in one query, none for an empty page`() {
        val repository = mock(YachtRepository::class.java)
        `when`(repository.findPublicSlugs(anyCollection())).thenReturn(listOf(arrayOf<Any>(7L, "gulet-7")))

        YachtPublicSlug.byId(repository, listOf(7L, 8L, 7L)) shouldBe mapOf(7L to "gulet-7")

        val unused = mock(YachtRepository::class.java)
        YachtPublicSlug.byId(unused, emptyList()) shouldBe emptyMap()
        verifyNoInteractions(unused)
    }
}
