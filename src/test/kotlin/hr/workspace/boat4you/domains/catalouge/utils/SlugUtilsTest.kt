package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The computed boat slug; every old slug still resolves by its trailing id (YachtController) and 308s to this one. */
class SlugUtilsTest {
    @Test
    fun `R31 - a name that repeats the model is left out`() {
        SlugUtils.toSlugWithId("Custom", "Gulet", "Gulet", 18992) shouldBe "custom-gulet-18992"
        SlugUtils.toSlugWithId(null, "Gulet", "Gulet", 18992) shouldBe "gulet-18992"
        SlugUtils.toSlugWithId(null, "MY Custom Anthea", "Anthea", 7) shouldBe "my-custom-anthea-7"
        SlugUtils.toSlugWithId("Lagoon", "Lagoon 42", "Lagoon 42", 5) shouldBe "lagoon-42-5"
    }

    @Test
    fun `a name of its own stays, the maker-prefix rule is unchanged`() {
        SlugUtils.toSlugWithId("Lagoon", "Lagoon 42", "My Lagoon", 5) shouldBe "lagoon-42-my-lagoon-5"
        SlugUtils.toSlugWithId("Leopard Catamarans", "Leopard 42", "Boat4You 424/4/4 Premium", 9946) shouldBe
            "leopard-catamarans-leopard-42-boat4you-42444-premium-9946"
        SlugUtils.toSlugWithId("Bali Catamarans", "Bali 4.6", "Bali", 3) shouldBe "bali-catamarans-bali-46-bali-3"
        SlugUtils.toSlugWithId("Lagoon", "Lagoon", "Sea Breeze", 4) shouldBe "lagoon-sea-breeze-4"
    }

    @Test
    fun `empty parts`() {
        SlugUtils.toSlugWithId(null, null, null, 1) shouldBe "1"
        SlugUtils.toSlugWithId(null, "Gulet", "", 2) shouldBe "gulet-2"
        SlugUtils.toSlugWithId(null, null, "Gulet", 3) shouldBe "gulet-3"
    }
}
