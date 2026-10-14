package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R02 flag F11: the charter agreement identifies the vessel by the partner's own name - a legal identification; the agency
 * is already the named Operator party. The public name ("Boat4You ...") would misidentify the boat.
 */
class CharterAgreementVesselLabelTest {
    private fun yacht(
        name: String,
        partnerName: String?,
        model: String = "Leopard 42",
        maker: String = "Leopard Catamarans",
    ) = Yacht().apply {
        this.name = name
        this.partnerName = partnerName
        this.model =
            Model().apply {
                this.name = model
                manufacturer = Manufacturer().apply { this.name = maker }
            }
    }

    @Test
    fun `the vessel is named by its partner name`() {
        CharterAgreementService.vesselLabel(yacht("Boat4You 424/4/4 Premium", "Sunsail 424/4/4 Premium")) shouldBe
            "Leopard 42 Sunsail 424/4/4 Premium"
        CharterAgreementService.vesselLabel(yacht("Boat4You", "Active Cruises", "Lagoon 47", "Lagoon")) shouldBe
            "Lagoon 47 Active Cruises"
    }

    @Test
    fun `a boat the policy never renamed keeps its name, the maker-prefix rule is unchanged`() {
        CharterAgreementService.vesselLabel(yacht("Pampero", null, "Oceanis 46.1", "Bénéteau")) shouldBe "Bénéteau Oceanis 46.1 Pampero"
        CharterAgreementService.vesselLabel(yacht("Pampero", "Pampero", "Bali 4.2", "Bali Catamarans")) shouldBe "Bali 4.2 Pampero"
    }
}
