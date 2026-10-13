package hr.workspace.boat4you.domains.chat.service

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** The blog guides the chat may link: published slugs from the site's blog sitemap, titles from the post pages. */
class ChatBlogIndexTest {
    private var sitemapStatus = 200
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val (status, body) =
                    when (ex.requestURI.path) {
                        "/sitemap-blogs.xml" ->
                            sitemapStatus to
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                                  <url><loc>https://www.boat4you.com/blog/croatia-vs-greece-yacht-charter-2027-comparison</loc></url>
                                  <url><loc>https://www.boat4you.com/blog/7-day-itinerary-from-split-croatia</loc></url>
                                  <url><loc>https://www.boat4you.com/boat/lagoon-42-1234</loc></url>
                                </urlset>
                                """.trimIndent()
                        "/blog/croatia-vs-greece-yacht-charter-2027-comparison" ->
                            200 to "<html><head><title>Croatia vs Greece Sailing: Prices &amp; Routes | Boat4You</title></head></html>"
                        else -> 404 to "not found"
                    }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    private val index = ChatBlogIndex("http://127.0.0.1:${server.address.port}")

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `published blog slugs with their page titles, slug text when a page has none`() {
        index.refresh()
        index.bySlug("croatia-vs-greece-yacht-charter-2027-comparison")?.title shouldBe "Croatia vs Greece Sailing: Prices & Routes"
        index.bySlug("7-day-itinerary-from-split-croatia")?.title shouldBe "7 day itinerary from split croatia"
        index.bySlug("7-day-itinerary-from-split-croatia")?.path shouldBe "/blog/7-day-itinerary-from-split-croatia"
        index.bySlug("lagoon-42-1234") shouldBe null
        index.bySlug("made-up-guide") shouldBe null
    }

    @Test
    fun `a failed refresh keeps the last good list, none at first`() {
        sitemapStatus = 500
        runCatching { index.refresh() }
        index.bySlug("7-day-itinerary-from-split-croatia") shouldBe null

        sitemapStatus = 200
        index.refresh()
        sitemapStatus = 500
        runCatching { index.refresh() }
        index.bySlug("7-day-itinerary-from-split-croatia")?.slug shouldBe "7-day-itinerary-from-split-croatia"
    }

    @Test
    fun `titles are plain text`() {
        ChatBlogIndex.cleanTitle("Best <b>Time</b> to Sail &ndash; Med | Boat4You") shouldBe "Best b Time /b to Sail – Med"
        ChatBlogIndex.cleanTitle("Kornati Guide - boat4you") shouldBe "Kornati Guide"
        ChatBlogIndex.cleanTitle("   ") shouldBe null
        ChatBlogIndex.slugsOf("<loc>https://evil.example/blog/../x</loc>").shouldBeEmpty()
    }
}
