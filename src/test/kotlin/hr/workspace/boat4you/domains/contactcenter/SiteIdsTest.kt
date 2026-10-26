package hr.workspace.boat4you.domains.contactcenter

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SiteIdsTest {
    private val apex =
        linkedMapOf(
            "boat4you.com" to "boat4you",
            "catamaran-croatia-charter.com" to "catamaran-croatia",
            "catamaran-charter-greece.com" to "catamaran-greece",
            "catamarancharteritaly.com" to "catamaran-italy",
            "catamaran-charter-caribbean.com" to "catamaran-caribbean",
            "europe-yachts.com" to "europe-yachts",
            "croatia-yachting.com" to "croatia-yachting",
        )

    @Test
    fun `every site resolves from its apex and www host`() {
        apex.forEach { (host, id) ->
            SiteIds.fromHost(host) shouldBe id
            SiteIds.fromHost("www.$host") shouldBe id
        }
        SiteIds.ALL shouldBe apex.values.toList()
        SiteIds.HOSTS shouldBe apex
    }

    @Test
    fun `case, port, spaces and a trailing dot do not matter`() {
        SiteIds.fromHost("WWW.Boat4You.COM") shouldBe "boat4you"
        SiteIds.fromHost("www.boat4you.com:443") shouldBe "boat4you"
        SiteIds.fromHost("croatia-yachting.com.") shouldBe "croatia-yachting"
        SiteIds.fromHost(" europe-yachts.com ") shouldBe "europe-yachts"
    }

    @Test
    fun `lookalikes, other subdomains and the old Italy domain are no site`() {
        listOf(
            "boat4you.com.evil.tld",
            "evilboat4you.com",
            "api.boat4you.com",
            "admin.boat4you.com",
            "www.www.boat4you.com",
            "catamaran-charter-italy.com",
            "localhost",
            "",
            "www.",
        ).forEach { SiteIds.fromHost(it).shouldBeNull() }
    }

    @Test
    fun `only the exact ids are known`() {
        SiteIds.ALL.forEach { SiteIds.isKnown(it) shouldBe true }
        listOf("Boat4You", "", "croatia-yachting ").forEach { SiteIds.isKnown(it) shouldBe false }
    }
}
