package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.service.PageTypeClassifier.PageType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The page type and boat slug the live view shows, derived from the path alone (each site has its own URL scheme). */
class PageTypeClassifierTest {
    private fun t(
        site: String,
        path: String,
    ) = PageTypeClassifier.classify(site, path)

    @Test
    fun `page types per site`() {
        t("boat4you", "/hr/boat/lagoon-42-5217?startDate=x") shouldBe PageType("BOAT", "lagoon-42-5217")
        t("boat4you", "/") shouldBe PageType("HOME", null)
        t("boat4you", "/de") shouldBe PageType("HOME", null)
        t("boat4you", "/yachts/lagoon/42").type shouldBe "OTHER"
        t("boat4you", "/search?location=Split").type shouldBe "SEARCH"
        t("boat4you", "/enter-your-details").type shouldBe "BOOKING"
        t("boat4you", "/itineraries/split").type shouldBe "ITINERARY"
        t("catamaran-greece", "/catamarans/lagoon-40-alma") shouldBe PageType("BOAT", "lagoon-40-alma")
        t("catamaran-greece", "/catamarans").type shouldBe "SEARCH"
        t("catamaran-greece", "/fleet/2").type shouldBe "SEARCH"
        t("croatia-yachting", "/yachts/x-1") shouldBe PageType("BOAT", "x-1")
        t("europe-yachts", "/yacht/x-1") shouldBe PageType("BOAT", "x-1")
        t("catamaran-italy", "/destinations/sardinia").type shouldBe "DESTINATION"
        t("catamaran-croatia", "/blog/a").type shouldBe "BLOG"
        t("catamaran-caribbean", "/wizard").type shouldBe "BOOKING"
        t("croatia-yachting", "/about").type shouldBe "OTHER"
    }
}
