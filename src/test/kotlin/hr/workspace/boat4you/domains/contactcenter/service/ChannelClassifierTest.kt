package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.contactcenter.service.ChannelClassifier.Attribution
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Where a visit came from: the first matching rule wins, from a sister-site handoff down to plain direct. */
class ChannelClassifierTest {
    private val c = ChannelClassifier(TrackerUrlPolicy(BrandRegistry(), ""))

    private fun on(
        site: String = "boat4you",
        ref: String? = null,
        utm: Utm = Utm(),
        gclid: String? = null,
        fbclid: String? = null,
        params: Set<String> = emptySet(),
        handoff: String? = null,
        ccSrc: String? = null,
    ) = c.classify(site, ref, utm, gclid, fbclid, params, handoff, ccSrc)

    @Test
    fun `channel precedence`() {
        on(handoff = "catamaran-greece", gclid = "x") shouldBe Attribution("SISTER", "catamaran-greece")
        on(ccSrc = "catamaran-italy", gclid = "x") shouldBe Attribution("SISTER", "catamaran-italy") // P1c AI "book now" link
        on(ccSrc = "boat4you").channel shouldBe "DIRECT" // own site is no sister
        on(gclid = "x").channel shouldBe "ADS"
        on(params = setOf("gbraid")).channel shouldBe "ADS"
        on(utm = Utm(medium = "CPC")).channel shouldBe "ADS"
        on(utm = Utm(medium = "email")).channel shouldBe "EMAIL"
        on(ref = "l.facebook.com").channel shouldBe "SOCIAL"
        on(fbclid = "y").channel shouldBe "SOCIAL"
        on(ref = "catamaran-charter-greece.com") shouldBe Attribution("SISTER", "catamaran-greece")
        on(ref = "www.boat4you.com").channel shouldBe "DIRECT"
        on(ref = "www.google.hr").channel shouldBe "ORGANIC"
        on(ref = "duckduckgo.com").channel shouldBe "ORGANIC"
        on(ref = "www.bing.com").channel shouldBe "ORGANIC"
        on(ref = "mail.google.com").channel shouldBe "EMAIL"
        on(utm = Utm(source = "partner")).channel shouldBe "OTHER"
        on(ref = "www.yachtcharterfleet.com").channel shouldBe "REFERRAL"
        on().channel shouldBe "DIRECT"
    }

    @Test
    fun `the lists behind the rules are anchored and case-insensitive where they should be`() {
        on(params = setOf("msclkid")).channel shouldBe "ADS"
        on(utm = Utm(medium = "Social")).channel shouldBe "SOCIAL"
        on(ref = "www.pinterest.co.uk").channel shouldBe "SOCIAL"
        on(ref = "evil-facebook.com").channel shouldBe "REFERRAL" // the dot (or the start) before the social host is required
        on(ref = "search.yahoo.com").channel shouldBe "ORGANIC"
        on(ref = "search.brave.com").channel shouldBe "ORGANIC"
        on(ref = "yandex.ru").channel shouldBe "ORGANIC"
        on(ref = "outlook.live.com").channel shouldBe "EMAIL"
        on(utm = Utm(campaign = "spring")).channel shouldBe "OTHER"
        on(ccSrc = "not-a-site").channel shouldBe "DIRECT" // only a known site id counts as a sister
        on(site = "catamaran-greece", ref = "catamaran-charter-greece.com").channel shouldBe "DIRECT"
    }
}
