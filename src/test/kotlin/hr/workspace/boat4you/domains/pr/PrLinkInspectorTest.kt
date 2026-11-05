package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.links.FetchResult
import hr.workspace.boat4you.domains.pr.links.FoundLink
import hr.workspace.boat4you.domains.pr.links.LinkInspection
import hr.workspace.boat4you.domains.pr.links.PrLinkInspector
import hr.workspace.boat4you.domains.pr.service.PrProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/** Finding our links in an article (Jsoup) and turning a fetch + inspection into a PrLinkState. Pure, no network. */
class PrLinkInspectorTest {
    private val ours = PrProperties.OUR_DOMAINS

    private fun fixture(name: String) = ClassPathResource("pr/$name").inputStream.readAllBytes().decodeToString()

    private fun ok(
        html: String? = "<html></html>",
        xRobotsTag: String? = null,
    ) = FetchResult(200, "https://www.example-news.co.uk/travel/a", html, xRobotsTag)

    private fun state(
        html: String,
        xRobotsTag: String? = null,
    ) = PrLinkInspector.classify(ok(html, xRobotsTag), PrLinkInspector.inspect(html, ours, "https://www.example-news.co.uk/travel/a"))

    @Test
    fun inspectFindsFollowLink() {
        val inspection = PrLinkInspector.inspect(fixture("article-follow.html"), ours, "https://www.example-news.co.uk/travel/a")

        inspection.links shouldContainExactly listOf(FoundLink("https://www.boat4you.com/press/x", setOf("noopener"), "Boat4you's study"))
        inspection.metaNoindex shouldBe false
        inspection.metaNofollow shouldBe false
        state(fixture("article-follow.html")) shouldBe PrLinkState.OK_FOLLOW
    }

    @Test
    fun inspectFindsNofollowAndSponsored() {
        val inspection = PrLinkInspector.inspect(fixture("article-nofollow.html"), ours)

        inspection.links shouldContainExactly
            listOf(
                FoundLink("https://catamaran-croatia-charter.com/press/prices", setOf("nofollow"), "Catamaran Croatia Charter"),
                FoundLink("https://www.croatia-yachting.com/deals", setOf("sponsored", "noopener"), "Croatia Yachting"),
                FoundLink("http://catamarancharteritaly.com/", setOf("ugc"), "a reader"),
            )
        state(fixture("article-nofollow.html")) shouldBe PrLinkState.OK_NOFOLLOW
        // One followed link among nofollow ones is enough for OK_FOLLOW.
        state(fixture("article-nofollow.html").replace("</article>", """<a href="https://www.boat4you.com/">b</a></article>""")) shouldBe PrLinkState.OK_FOLLOW
    }

    @Test
    fun subdomainCountsAsOurs() {
        val inspection = PrLinkInspector.inspect(fixture("article-mixed-domains.html"), ours, "https://www.example-news.co.uk/")

        inspection.links.map { it.href } shouldContainExactly listOf("https://wp.europe-yachts.com/blog/2027-trends")
        PrLinkInspector.inspect("""<a href="HTTPS://WWW.Boat4You.COM./x">x</a>""", ours).links.size shouldBe 1
        PrLinkInspector.inspect("""<a href="//www.catamaran-charter-greece.com/">x</a>""", ours, "https://news.example/a").links.size shouldBe 1
    }

    @Test
    fun otherDomainsIgnored() {
        val html = fixture("article-mixed-domains.html").replace("https://wp.europe-yachts.com/blog/2027-trends", "https://europe-yachts.net/")

        PrLinkInspector.inspect(html, ours, "https://www.example-news.co.uk/").links.shouldBeEmpty()
        state(html) shouldBe PrLinkState.LINK_MISSING
        state(fixture("article-no-link.html")) shouldBe PrLinkState.LINK_MISSING
    }

    @Test
    fun relativeLinksResolveAgainstThePageUrl() {
        val html = """<a href="/charter/split">Split</a>"""

        PrLinkInspector.inspect(html, ours, "https://www.croatia-yachting.com/blog/post").links shouldContainExactly
            listOf(FoundLink("https://www.croatia-yachting.com/charter/split", emptySet(), "Split"))
        PrLinkInspector.inspect(html, ours, "https://www.example-news.co.uk/a").links.shouldBeEmpty()
        PrLinkInspector.inspect(html, ours).links.shouldBeEmpty()
    }

    @Test
    fun metaRobotsAndXRobotsTagMakeTheLinkNofollow() {
        val inspection = PrLinkInspector.inspect(fixture("article-meta-robots.html"), ours)
        inspection.metaNoindex shouldBe true
        inspection.links.size shouldBe 1
        state(fixture("article-meta-robots.html")) shouldBe PrLinkState.OK_NOFOLLOW

        val follow = fixture("article-follow.html")
        state(follow.replace("<head>", """<head><meta name="robots" content="index,nofollow">""")) shouldBe PrLinkState.OK_NOFOLLOW
        state(follow.replace("<head>", """<head><meta name="googlebot" content="none">""")) shouldBe PrLinkState.OK_NOFOLLOW
        state(follow.replace("<head>", """<head><meta name="description" content="nofollow noindex">""")) shouldBe PrLinkState.OK_FOLLOW
        state(follow, xRobotsTag = "noarchive, NoFollow") shouldBe PrLinkState.OK_NOFOLLOW
        state(follow, xRobotsTag = "googlebot: noindex") shouldBe PrLinkState.OK_NOFOLLOW
        state(follow, xRobotsTag = "noarchive") shouldBe PrLinkState.OK_FOLLOW
    }

    @Test
    fun classifyByStatus() {
        val anyLink = LinkInspection(listOf(FoundLink("https://www.boat4you.com/", emptySet(), "b")), metaNoindex = false)
        fun status(code: Int) = PrLinkInspector.classify(FetchResult(code, "https://n.example/a", null, null), anyLink)

        listOf(-1, 401, 402, 403, 429, 503, 500, 502, 301, 999).forEach { status(it) shouldBe PrLinkState.BLOCKED_UNKNOWN }
        listOf(404, 410).forEach { status(it) shouldBe PrLinkState.PAGE_GONE }
        status(200) shouldBe PrLinkState.OK_FOLLOW
        PrLinkInspector.classify(FetchResult(200, "https://n.example/a", null, null), null) shouldBe PrLinkState.LINK_MISSING
    }
}
