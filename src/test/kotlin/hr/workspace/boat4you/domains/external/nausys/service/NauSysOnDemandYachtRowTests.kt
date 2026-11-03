package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which of our yacht rows an on-demand NauSys answer is written to (bug 2.11.2026): a boat re-registered under another
 * agency keeps its NauSys id, gets a new active row, and the old row stays deactivated with its mapping. The 14-day
 * offer of Namaka Ekolu (NauSys 67732855) went to the retired row 2466, so the page (row 19986) said "not available".
 */
class NauSysOnDemandYachtRowTests {
    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, "Yacht", null, null)

    private fun yacht(
        id: Long,
        active: Boolean,
    ) = Yacht().apply {
        this.id = id
        sysActive = active
    }

    @Test
    fun `the active row wins over a retired twin mapped to the same NauSys yacht, whatever the mapping order`() {
        val yachts = listOf(yacht(2466, active = false), yacht(19986, active = true))
        val retiredFirst = listOf(mapping(67732855, 2466), mapping(67732855, 19986))
        NauSysYachtOfferSyncService.onDemandYachtRow(67732855, retiredFirst, yachts) shouldBe 19986L
        NauSysYachtOfferSyncService.onDemandYachtRow(67732855, retiredFirst.reversed(), yachts) shouldBe 19986L
    }

    @Test
    fun `a single mapping is used as before, active or not`() {
        NauSysYachtOfferSyncService.onDemandYachtRow(7, listOf(mapping(7, 70)), listOf(yacht(70, active = true))) shouldBe 70L
        NauSysYachtOfferSyncService.onDemandYachtRow(7, listOf(mapping(7, 70)), listOf(yacht(70, active = false))) shouldBe 70L
    }

    @Test
    fun `no row when the mapped yacht is not loaded or the NauSys yacht is not mapped`() {
        NauSysYachtOfferSyncService.onDemandYachtRow(7, listOf(mapping(7, 70)), emptyList()).shouldBeNull()
        NauSysYachtOfferSyncService.onDemandYachtRow(8, listOf(mapping(7, 70)), listOf(yacht(70, active = true))).shouldBeNull()
    }
}
