package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.mentions.FeedItem
import hr.workspace.boat4you.domains.pr.mentions.PrFeedParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.Instant

/** Feed parsing (Atom and RSS 2.0, XXE-safe) and the URL normalisation that keeps one article one mention. No network. */
class PrFeedParserTest {
    private fun fixture(name: String) = ClassPathResource("pr/$name").inputStream.readAllBytes().decodeToString()

    @Test
    fun parsesGoogleAlertsAtom() {
        PrFeedParser.parse(fixture("google-alerts.atom.xml")) shouldContainExactly
            listOf(
                FeedItem(
                    "https://www.google.com/url?rct=j&sa=t&url=https://www.Example-News.co.uk/travel/Croatia-Charter-Prices%3Fid%3D7%26utm_source%3Dgoogle" +
                        "%26utm_medium%3Dalert%23comments&ct=ga&cd=CAIyGjE4ZjQ2YzQ5&usg=AOvVaw0Xk1",
                    "Croatia yacht charter prices up 8%, says Boat4you study",
                    Instant.parse("2026-11-04T07:45:00Z"),
                ),
                FeedItem(
                    "https://www.google.com/url?rct=j&sa=t&url=https://blog.catamaran-croatia-charter.com/2027-guide&ct=ga&cd=CAIyGjE4ZjQ2YzQ6&usg=AOvVaw0Xk2",
                    "Catamaran charter in Croatia: the 2027 guide | Boat4you",
                    Instant.parse("2026-11-04T06:30:00Z"),
                ),
                // No <published>: <updated> stands in; HTML and double-escaped entities in the title come out as text.
                FeedItem(
                    "https://www.google.com/url?rct=j&sa=t&url=https://travelwire.example/2026/11/sailing-holiday-costs%3Futm_campaign%3Dnewsletter" +
                        "%26fbclid%3DIwAR0abc&ct=ga&cd=CAIyGjE4ZjQ2YzQ7&usg=AOvVaw0Xk3",
                    "A skipper's view: what a sailing week costs & why (Boat4you)",
                    Instant.parse("2026-11-03T21:05:00Z"),
                ),
            )
    }

    @Test
    fun parsesTalkwalkerRss() {
        PrFeedParser.parse(fixture("talkwalker.rss.xml")) shouldContainExactly
            listOf(
                FeedItem(
                    "https://www.example-news.co.uk/travel/Croatia-Charter-Prices?utm_source=talkwalker&id=7&UTM_Medium=rss",
                    "Croatia yacht charter prices up 8%, says Boat4you study",
                    Instant.parse("2026-11-04T08:10:00Z"),
                ),
                FeedItem(
                    "https://www.yachting-digest.example/news/greek-fleet-report?page=2&gclid=Cj0KCQ#comments",
                    "Boat4you & the Greek islands: fleet report",
                    Instant.parse("2026-11-03T17:30:00Z"),
                ),
                // An unparseable pubDate is no error: published is null.
                FeedItem("https://www.boat4you.com/en/catamaran-charter-italy", "Sailing in Italy with a catamaran", null),
            )
    }

    @Test
    fun atomLinkPicksAlternateAndSkipsEntriesWithoutOne() {
        val xml =
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>T1</title><link rel="self" href="https://feeds.example/self"/><link rel="alternate" href="https://news.example/one"/></entry>
              <entry><title>T2</title><link rel="enclosure" href="https://cdn.example/x.mp3"/></entry>
              <entry><link href="https://news.example/three"/><published>not a date</published></entry>
            </feed>
            """.trimIndent()
        PrFeedParser.parse(xml) shouldContainExactly
            listOf(FeedItem("https://news.example/one", "T1", null), FeedItem("https://news.example/three", "", null))
    }

    @Test
    fun longTitleIsCutTo500() {
        val xml = "<rss version=\"2.0\"><channel><item><title>${"x".repeat(700)}</title><link>https://news.example/a</link></item></channel></rss>"
        PrFeedParser.parse(xml).single().title.length shouldBe 500
    }

    @Test
    fun googleRedirectAndUtmAreNormalised() {
        val viaGoogle = PrFeedParser.parse(fixture("google-alerts.atom.xml")).first().url
        val direct = PrFeedParser.parse(fixture("talkwalker.rss.xml")).first().url
        val expected = "https://www.example-news.co.uk/travel/Croatia-Charter-Prices?id=7"
        PrFeedParser.normaliseUrl(viaGoogle) shouldBe expected
        PrFeedParser.normaliseUrl(direct) shouldBe expected
        PrFeedParser.normaliseUrl("HTTPS://WWW.EXAMPLE-NEWS.CO.UK/travel/Croatia-Charter-Prices?id=7#top") shouldBe expected

        // q= when there is no url=, other parameters kept in order, an empty "?" dropped.
        PrFeedParser.normaliseUrl("https://google.com/url?q=https%3A%2F%2Fnews.example%2Fa%3Fb%3D2%26a%3D1%26gclid%3Dx&sa=D") shouldBe "https://news.example/a?b=2&a=1"
        PrFeedParser.normaliseUrl("https://news.example/a?utm_source=x&FBCLID=y&Utm_Term=z") shouldBe "https://news.example/a"
        PrFeedParser.normaliseUrl("https://news.example/a?") shouldBe "https://news.example/a"
        PrFeedParser.normaliseUrl("  https://news.example:8443/A/b?x=1&utm_id=2&y  ") shouldBe "https://news.example:8443/A/b?x=1&y"
        PrFeedParser.normaliseUrl(PrFeedParser.parse(fixture("talkwalker.rss.xml"))[1].url) shouldBe "https://www.yachting-digest.example/news/greek-fleet-report?page=2"

        listOf("ftp://news.example/a", "mailto:desk@news.example", "https://google.com/url?url=javascript:alert(1)", "not a url", "https:///nohost", "https://u:p@news.example/a")
            .forEach { raw -> shouldThrow<IllegalArgumentException> { PrFeedParser.normaliseUrl(raw) } }
    }

    @Test
    fun doctypeIsRejected() {
        // External entities (file:/// and http://) are never resolved: the DOCTYPE itself is refused before any of them.
        shouldThrow<IllegalArgumentException> { PrFeedParser.parse(fixture("xxe.rss.xml")) }.message.let {
            it shouldContain "DOCTYPE"
            it shouldNotContain "root:"
        }
        val billionLaughs =
            """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;"><!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">]>
            <rss version="2.0"><channel><item><title>&lol3;</title><link>https://news.example/a</link></item></channel></rss>
            """.trimIndent()
        shouldThrow<IllegalArgumentException> { PrFeedParser.parse(billionLaughs) }
        shouldThrow<IllegalArgumentException> { PrFeedParser.parse("<html><body>Not a feed</body></html>") }
        shouldThrow<IllegalArgumentException> { PrFeedParser.parse("<rss><channel><item>") }
    }
}
