package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.review.service.ReviewLabels
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R31 (owner flag F13): the reservation and review e-mails name the yacht Manufacturer + Model + Name (Mario rule
 * 3.5.2026) without a name that only repeats the model. The inquiry e-mail is LOCKED and does not use these labels.
 */
class YachtEmailLabelTest {
    @Test
    fun `reservation e-mails drop a repeated name and keep a name of its own`() {
        emailYachtLabel("Custom", "Gulet", "Gulet") shouldBe "Custom Gulet"
        emailYachtLabel(null, "MY Custom Anthea", "Anthea") shouldBe "MY Custom Anthea"
        emailYachtLabel("Beneteau", "Oceanis 46.1", "Tria") shouldBe "Beneteau Oceanis 46.1 Tria"
        emailYachtLabel("Lagoon", "Lagoon 42", "My Lagoon") shouldBe "Lagoon Lagoon 42 My Lagoon"
        emailYachtLabel(null, null, "Fortuna") shouldBe "Fortuna"
        emailYachtLabel(null, null, null) shouldBe "—"
        emailYachtLabel("", "", "") shouldBe ""
    }

    @Test
    fun `review labels drop a repeated name`() {
        ReviewLabels.yachtFullLabel(null, "Gulet", "Gulet") shouldBe "Gulet"
        ReviewLabels.yachtFullLabel("Bavaria", "Bavaria Cruiser 46", "Tria") shouldBe "Bavaria Cruiser 46 Tria"
        ReviewLabels.yachtFullLabel("Custom", "MS Custom Aurum Sky", "M/S Aurum Sky") shouldBe "Custom MS Custom Aurum Sky"
    }
}
