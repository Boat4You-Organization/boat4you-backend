package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.chat.service.AiChatService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The URL, origin and referrer rules of the tracker: a page must belong to the claimed site, token pages are never kept. */
class TrackerUrlPolicyTest {
    private val policy = TrackerUrlPolicy(BrandRegistry(), "")

    @Test
    fun `host must belong to the claimed site and the stored url is rebuilt from BrandRegistry`() {
        val raw = "https://www.boat4you.com/hr/boat/lagoon-42-5217?startDate=2027-07-17&utm_source=x&gclid=abc&cch=T&cc_src=catamaran-greece"
        val ok = policy.clean("boat4you", raw) as UrlCheck.Ok
        ok.url.path shouldBe "/hr/boat/lagoon-42-5217?startDate=2027-07-17"
        ok.url.url shouldBe "https://www.boat4you.com/hr/boat/lagoon-42-5217?startDate=2027-07-17"
        ok.url.params["utm_source"] shouldBe "x"
        ok.url.params["gclid"] shouldBe "abc"
        ok.url.params["cc_src"] shouldBe "catamaran-greece" // P1c's AI "book now" links; attribution in Task 5
        (policy.clean("boat4you", "https://www.boat4you.com") as UrlCheck.Ok).url.path shouldBe "/"
        policy.clean("catamaran-greece", "https://www.boat4you.com/boat/x") shouldBe UrlCheck.ForeignHost
        policy.clean("boat4you", "https://www.boat4you.com.evil.tld/boat/x") shouldBe UrlCheck.ForeignHost
        listOf(
            "https://user@www.boat4you.com/",
            "javascript:alert(1)",
            "https://www.boat4you.com//evil.tld/x",
            "ftp://www.boat4you.com/x",
            "http://www.boat4you.com/x",
            "https://www.boat4you.com/" + "a".repeat(1000),
            "https://www.boat4you.com/a b",
        ).forEach { policy.clean("boat4you", it) shouldBe UrlCheck.Invalid }
    }

    @Test
    fun `token pages are never stored`() { // WEB-18
        listOf(
            "/hr/review/abc?rating=5",
            "/unsubscribe/tok",
            "/en/confirm-email-change?token=t",
            "/custom-offers/h1",
            "/forgot-password?passwordResetCode=9",
            "/trip/tok",
            "/boat/x?token=t",
            "/hr/%72eview/abc?rating=5",
            "/review%2Fabc",
            "/boat/x?to%6Ben=t",
            "/boat/x?%74oken=t",
        ).forEach { policy.clean("boat4you", "https://www.boat4you.com$it") shouldBe UrlCheck.Secret }
    }

    @Test
    fun `SAFE_PAGE stays byte-identical to the chat rule`() { // BACKEND-25
        listOf(
            "/hr/boat/lagoon-42-5217?startDate=2027-07-17&endDate=2027-07-24",
            "//evil.tld/x",
            "/\\evil",
            "/x y",
            "/@evil",
            "/" + "a".repeat(499),
            "/" + "a".repeat(500),
        ).forEach { p -> TrackerUrlPolicy.SAFE_PAGE.matches(p) shouldBe AiChatService.safePageUrl(p).isNotEmpty() }
    }

    @Test
    fun `origin must be a host of the claimed site`() {
        policy.originMatches("https://catamaran-charter-greece.com", "catamaran-greece") shouldBe true
        policy.originMatches("https://www.boat4you.com", "catamaran-greece") shouldBe false
        policy.originMatches(null, "boat4you") shouldBe false
        policy.originMatches("null", "boat4you") shouldBe false
        TrackerUrlPolicy(BrandRegistry(), "localhost").originMatches("http://localhost:3000", "europe-yachts") shouldBe true
    }

    @Test
    fun `referrer and text are kept as plain bounded text`() {
        policy.referrer("https://www.google.hr/") shouldBe CleanReferrer("https://www.google.hr/", "www.google.hr")
        policy.referrer("https://user@evil.tld/") shouldBe null
        policy.referrer(null) shouldBe null
        // WEB-18: a same-origin referrer carries the full URL, so a token page keeps only its origin
        policy.referrer("https://www.boat4you.com/trip/abc123") shouldBe CleanReferrer("https://www.boat4you.com/", "www.boat4you.com")
        policy.referrer("https://www.boat4you.com/en/confirm-email-change?token=t") shouldBe CleanReferrer("https://www.boat4you.com/", "www.boat4you.com")
        policy.referrer("https://www.boat4you.com/hr/%72eview/abc") shouldBe CleanReferrer("https://www.boat4you.com/", "www.boat4you.com")
        policy.referrer("https://www.boat4you.com/boat/x?to%6Ben=t") shouldBe CleanReferrer("https://www.boat4you.com/", "www.boat4you.com")
        TrackerText.clip("  a\u0000b\nc  ", 200) shouldBe "abc"
        TrackerText.clip("x".repeat(301), 300) shouldBe "x".repeat(300)
        TrackerText.clip(" \n ", 10) shouldBe null
    }

    @Test
    fun `an encoded page that is not a token page is stored as sent`() {
        (policy.clean("boat4you", "https://www.boat4you.com/hr/boat/%C4%8Damac-1") as UrlCheck.Ok).url.path shouldBe "/hr/boat/%C4%8Damac-1"
    }
}
