package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.services.ManufacturerAliasResolver
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** R02 model guards G3 / G4 / G5 (14.10.2026). */
class BrandModelAliasesTest {
    @Test
    fun `the shipped alias file holds the certain V9_84 pairs, nothing else`() {
        BrandModelAliases.aliases.map { "${it.system}|${it.label}|${it.targetModelId}|${it.targetModelName}" } shouldBe
            listOf("MMK|sunsail 424|2234|Leopard 42", "MMK|moorings 42.3|2716|Oceanis 40.1", "MMK|moorings 352|2701|Oceanis 35.1")
    }

    @Test
    fun `a label resolves per partner system, case and spacing ignored`() {
        BrandModelAliases.aliasFor(ExternalSystemEnum.MMK, " Sunsail  424 ")?.targetModelId shouldBe 2234L
        BrandModelAliases.aliasFor(ExternalSystemEnum.NAUSYS, "Sunsail 424") shouldBe null
        BrandModelAliases.aliasFor(ExternalSystemEnum.MMK, "Sunsail 41") shouldBe null
        BrandModelAliases.aliasFor(ExternalSystemEnum.MMK, null) shouldBe null
    }

    @Test
    fun `an alias is used only while the target row keeps the expected name`() {
        val alias = BrandModelAliases.aliasFor(ExternalSystemEnum.MMK, "Sunsail 424")!!
        BrandModelAliases.matchesTarget(alias, "Leopard 42") shouldBe true
        BrandModelAliases.matchesTarget(alias, "leopard  42") shouldBe true
        BrandModelAliases.matchesTarget(alias, "Leopard 45") shouldBe false
        BrandModelAliases.matchesTarget(alias, null) shouldBe false
    }

    @Test
    fun `a malformed line fails loudly`() {
        shouldThrow<IllegalArgumentException> { BrandModelAliases.parse(listOf("MMK|sunsail 424|2234")) }
        BrandModelAliases.parse(listOf("# comment", "", "NAUSYS|Moorings 4200|2240|Leopard 42 # note")).single().label shouldBe
            "moorings 4200"
    }

    @Test
    fun `G5 - charter brands are never a model's manufacturer, builders are`() {
        listOf("Sunsail", "The Moorings", "moorings", "NSS", "NSS Charter").forEach { BrandModelAliases.isBrandManufacturer(it) shouldBe true }
        listOf("Leopard Catamarans", "Bénéteau", "Dream Yacht Charter", null).forEach { BrandModelAliases.isBrandManufacturer(it) shouldBe false }
    }

    @Test
    fun `brand labels are recognised for the WARN`() {
        listOf("Sunsail 41", "Moorings 5000", "NSS 40", "Dream 60").forEach { BrandModelAliases.isBrandLabel(it) shouldBe true }
        listOf("Leopard 42", "Dreamer 30", "Oceanis 40.1", null).forEach { BrandModelAliases.isBrandLabel(it) shouldBe false }
    }

    @Test
    fun `G4 - Sunsail and The Moorings are never aliased to one builder`() {
        val resolver = ManufacturerAliasResolver()
        resolver.canonicalName("Sunsail") shouldBe "Sunsail"
        resolver.canonicalName("The Moorings") shouldBe "The Moorings"
        resolver.canonicalName("Moorings") shouldBe "Moorings"
        resolver.canonicalName("NSS") shouldBe "NSS"
    }
}
