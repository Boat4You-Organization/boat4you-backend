package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterBasis
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterDto
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterKind
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CRUISE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.UNKNOWN
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Admin Offers pill (Mario 9.10.2026): Bareboat / Skippered / Crewed per offer. The names are real obligatory partner
 * charges of future offers on prod (9.10.2026), including the ones that mention a skipper but are not one.
 */
class OfferCharterRulesTests {
    private val bareboat = OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT)

    @Test
    fun `a bareboat offer`() {
        OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), emptyList()) shouldBe bareboat
        OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), listOf("Transit log", "Final cleaning")) shouldBe bareboat
    }

    @Test
    fun `a bareboat product with an obligatory skipper is Skippered`() {
        OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), listOf("Transit log", "Skipper + food")) shouldBe
            OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "Skipper + food")
        // NauSys: no product on the offer, a bareboat boat
        OfferCharterRules.classify(false, UNKNOWN, setOf(BAREBOAT), listOf("Captain (meals not included)")).kind shouldBe OfferCharterKind.SKIPPERED
        OfferCharterRules.classify(false, null, emptySet(), listOf("Professional Captain RYA/MCA Yachtmaster on board - included ")).kind shouldBe
            OfferCharterKind.SKIPPERED
    }

    @Test
    fun `One man crew reads Skipper (ExtraNameNormalizer)`() {
        OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), listOf("One man crew (Caribbean)")) shouldBe
            OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "One man crew (Caribbean)")
    }

    @Test
    fun `charges that only mention a skipper keep the offer Bareboat`() {
        listOf(
            "Security Deposit* Valid ONLY for insured skippers OR mandatory damage waiver : refundable : € 1000.00",
            "6% to added on the invoice when the skipper is hired",
            "Temporary Belize Skipper Certificate fee",
            "Deckhand [Mandatory for Charters without our skipper. If skipper is selected, we provide deckhand free of charge]",
            "Skipper's liability insurance",
            "Additional fee for Skipper (one way)",
            "Checkout Skipper",
            "Starter Pack 2 (Bed linen in each cabin); Bath Towel set (1 bath towel + hand towel for each crew member)",
            "Crew list",
        ).forEach { name ->
            OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), listOf(name)) shouldBe bareboat
        }
    }

    @Test
    fun `crewed - product, crew-only boat, obligatory crew`() {
        OfferCharterRules.classify(false, CREWED, setOf(BAREBOAT, CREWED), emptyList()) shouldBe
            OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_PRODUCT)
        OfferCharterRules.classify(false, ALL_INCLUSIVE, setOf(ALL_INCLUSIVE), emptyList()).basis shouldBe OfferCharterBasis.CREWED_PRODUCT
        OfferCharterRules.classify(false, CRUISE, setOf(CRUISE), emptyList()).kind shouldBe OfferCharterKind.CREWED
        // crewed product wins over an obligatory skipper
        OfferCharterRules.classify(false, CREWED, setOf(CREWED), listOf("Skipper")).basis shouldBe OfferCharterBasis.CREWED_PRODUCT
        // NauSys offers carry no product: a boat listed crewed and never bareboat
        OfferCharterRules.classify(false, UNKNOWN, setOf(CREWED, ALL_INCLUSIVE), emptyList()) shouldBe
            OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_YACHT)
        // ... but a boat also sold bareboat decides by its charges
        OfferCharterRules.classify(false, UNKNOWN, setOf(BAREBOAT, CREWED), emptyList()) shouldBe bareboat
        // an MMK BAREBOAT product is bareboat even on a boat also listed crewed
        OfferCharterRules.classify(false, BAREBOAT, setOf(CREWED), emptyList()) shouldBe bareboat
        OfferCharterRules.classify(false, BAREBOAT, setOf(BAREBOAT), listOf("3 crew: 1 captain, 1 cook and 1 deckhand", "Skipper")) shouldBe
            OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, "3 crew: 1 captain, 1 cook and 1 deckhand")
        OfferCharterRules.classify(false, UNKNOWN, setOf(BAREBOAT), listOf("Crew - included")).basis shouldBe OfferCharterBasis.OBLIGATORY_CREW
    }

    @Test
    fun `a gulet is Crewed even when the partner tags it bareboat`() {
        val gulet = OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.GULET)
        OfferCharterRules.classify(true, BAREBOAT, setOf(BAREBOAT), emptyList()) shouldBe gulet
        OfferCharterRules.classify(true, BAREBOAT, setOf(BAREBOAT), listOf("Skipper")) shouldBe gulet
        OfferCharterRules.classify(true, null, emptySet(), emptyList()) shouldBe gulet
    }
}
