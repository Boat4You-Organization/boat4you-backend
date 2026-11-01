package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** The recording switches per site (P6 R1): off with full shares until the admin turns a site on; a change is seen at once. */
class RecSettingsStoreDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val store = RecSettingsStore(jdbc, Duration.ofSeconds(10))

    @Test
    fun `every site starts switched off, recording all and every mobile visit`() {
        store.all().map { it.siteId to Triple(it.enabled, it.samplePct, it.mobilePct) } shouldBe
            listOf("boat4you", "catamaran-croatia", "catamaran-greece", "catamaran-italy", "catamaran-caribbean", "europe-yachts", "croatia-yachting")
                .map { it to Triple(false, 100, 100) }
    }

    @Test
    fun `an update is stored with who made it and read back at once`() {
        store.forSite("catamaran-greece").enabled shouldBe false
        store.update(RecSiteSetting("catamaran-greece", true, 50, 25), userId = 7)
        store.forSite("catamaran-greece") shouldBe RecSiteSetting("catamaran-greece", true, 50, 25)
        jdbc.queryForObject("SELECT updated_by FROM rec_setting WHERE site_id = 'catamaran-greece'", Long::class.java) shouldBe 7
        store.update(RecSiteSetting("catamaran-greece", false, 50, 25), userId = 8)
        store.forSite("catamaran-greece").enabled shouldBe false
    }
}
