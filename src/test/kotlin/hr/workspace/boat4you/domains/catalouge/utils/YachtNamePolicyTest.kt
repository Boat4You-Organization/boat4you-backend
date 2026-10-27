package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** R02 (owner rule 14.10.2026): the public yacht name. */
class YachtNamePolicyTest {
    @Test
    fun `fleet labels and curated agency names become Boat4You, every other name is left alone`() {
        YachtNamePolicyFixtures.CASES.forEach { (raw, public) ->
            withClue(raw) { YachtNamePolicy.publicName(raw) shouldBe public }
        }
    }

    @Test
    fun `idempotent and never blank`() {
        YachtNamePolicyFixtures.CASES.forEach { (raw, _) ->
            val once = YachtNamePolicy.publicName(raw)
            withClue(raw) {
                YachtNamePolicy.publicName(once) shouldBe once
                once.isBlank() shouldBe false
            }
        }
        YachtNamePolicy.publicName("") shouldBe ""
        YachtNamePolicy.publicName("   ") shouldBe "   "
    }

    @Test
    fun `the case of the other words is untouched`() {
        YachtNamePolicy.publicName("Sunsail 454L PREMIUM plus") shouldBe "Boat4You 454L PREMIUM plus"
    }

    @Test
    fun `the curated list is the shipped file`() {
        YachtNamePolicy.brandNames shouldBe setOf("rhodes yachting", "active cruises", "sea novels")
    }

    @Test
    fun `a boat named after its own agency is reported, unless the curated list renames it`() {
        YachtNamePolicy.namedAfterOwnAgency("Zenith", "ZENITH ") shouldBe true
        YachtNamePolicy.namedAfterOwnAgency("Zenith", "Other Charter") shouldBe false
        YachtNamePolicy.namedAfterOwnAgency("Rhodes Yachting", "Rhodes Yachting") shouldBe false
        YachtNamePolicy.namedAfterOwnAgency(null, "Zenith") shouldBe false
    }

    @Test
    fun `trip texts use the public name, the model label when the public name is just Boat4You (flag F11)`() {
        YachtNamePolicy.tripName("Boat4You 424/4/4 Premium", "Leopard 42") shouldBe "Boat4You 424/4/4 Premium"
        YachtNamePolicy.tripName("Boat4You", "Lagoon 47") shouldBe "Lagoon 47"
        YachtNamePolicy.tripName(" boat4you ", "Lagoon 47") shouldBe "Lagoon 47"
        YachtNamePolicy.tripName("Boat4You", null) shouldBe "Boat4You"
        YachtNamePolicy.tripName("", "Lagoon 47") shouldBe "Lagoon 47"
        YachtNamePolicy.tripName(null, null) shouldBe null
        YachtNamePolicy.isBrandOnly("Boat4You 41") shouldBe false
    }

    @Test
    fun `applyTo - the MMK and NauSys syncs write the public name and keep the raw name internal on every write`() {
        val agency =
            Agency().apply {
                id = 5L
                name = "Zenith"
            }
        val yacht = Yacht()
        YachtNamePolicy.applyTo(yacht, "Sunsail 424/4/4 Premium", agency, "mmkYachtId=1")
        yacht.name shouldBe "Boat4You 424/4/4 Premium"
        yacht.partnerName shouldBe "Sunsail 424/4/4 Premium"

        // named like its own agency, not on the curated list: keeps its name (logged for review)
        YachtNamePolicy.applyTo(yacht, "Zenith", agency, "nausysYachtId=2")
        yacht.name shouldBe "Zenith"
        yacht.partnerName shouldBe "Zenith"

        YachtNamePolicy.applyTo(yacht, "Active Cruises", agency, "nausysYachtId=2")
        yacht.name shouldBe "Boat4You"
        yacht.partnerName shouldBe "Active Cruises"

        // a NauSys shell record without a name stays without one
        YachtNamePolicy.applyTo(yacht, null, agency, "nausysYachtId=3")
        yacht.name shouldBe null
        yacht.partnerName shouldBe null
    }
}
