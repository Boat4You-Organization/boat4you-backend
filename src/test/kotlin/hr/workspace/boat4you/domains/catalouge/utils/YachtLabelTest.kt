package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** R31: the b4y web's nameRepeatsModel cases (toTitleCase.ts, dcbec3ae8), ported. */
class YachtLabelTest {
    @Test
    fun `a name that repeats the model or its last words`() {
        listOf(
            "Gulet" to "Gulet",
            "Acapella" to "Acapella",
            "MY Custom Anthea" to "Anthea",
            "MS Custom Aurum Sky" to "M/S Aurum Sky",
            "Custom Aurum Sky" to "MS Aurum Sky",
            "Lagoon 42" to "lagoon 42",
            "Gulet Deniz Ruzgari" to "Deniz Rüzgari",
            "Sun Odyssey 410" to "Sun-Odyssey 410",
        ).forEach { (model, name) -> withClue("$model / $name") { YachtLabel.nameRepeatsModel(model, name) shouldBe true } }
    }

    @Test
    fun `a name of its own is kept`() {
        listOf(
            "Lagoon 42" to "My Lagoon",
            "Lagoon 42" to "42 Sunrise",
            "Gulet" to "Gulet Classic",
            "Leopard 42" to "Boat4You 424/4/4 Premium",
            "Oceanis 40.1" to "0.1",
            "Bali 4.6" to "",
            "" to "Gulet",
            null to "Gulet",
            "Gulet" to null,
        ).forEach { (model, name) -> withClue("$model / $name") { YachtLabel.nameRepeatsModel(model, name) shouldBe false } }
    }

    @Test
    fun `the comparison form`() {
        YachtLabel.nameCore("  M/S Aurum-Sky ") shouldBe "aurum sky"
        YachtLabel.nameCore("Deniz Rüzgâri") shouldBe "deniz ruzgari"
        YachtLabel.nameCore(null) shouldBe ""
    }
}
