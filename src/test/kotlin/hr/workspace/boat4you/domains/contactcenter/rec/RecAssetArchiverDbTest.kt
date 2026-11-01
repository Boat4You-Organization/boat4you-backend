package hr.workspace.boat4you.domains.contactcenter.rec

import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The asset archive (P6 R1.5): every Next.js stylesheet a recording uses is fetched once from its own site, stored by content
 * with the fonts it loads, and its url() references point to /replay-assets/<host>/, so a recording replays as it looked even
 * after the site was deployed again.
 */
class RecAssetArchiverDbTest {
    @TempDir lateinit var root: Path

    private val jdbc = ContactCenterTestDb.ready()
    private val hits = ConcurrentHashMap<String, Int>()
    private val css =
        "@font-face{font-family:x;src:url(/_next/static/media/f1.woff2) format(\"woff2\")}" +
            ".a{background:url(data:image/png;base64,AAAA)}.b{background:url('https://cdn.example.com/z.png')}"
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits.merge(ex.requestURI.path, 1, Int::plus)
                val body =
                    when (ex.requestURI.path) {
                        "/_next/static/css/a1.css" -> css.toByteArray()
                        "/_next/static/media/f1.woff2" -> byteArrayOf(1, 2, 3, 4)
                        else -> null
                    }
                if (body == null) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                ex.close()
            }
            start()
        }
    private val base = URI("http://127.0.0.1:${server.address.port}")
    private val archiver by lazy { RecAssetArchiver(jdbc, root.toString(), base.toString()) }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `stylesheets of the site are found in a recording, nothing from another host`() {
        RecAssetArchiver.stylesheets(
            """{"a":[[1,5,"href=/_next/static/css/a1.css","href=https://www.boat4you.com/_next/static/css/b2.css",""" +
                """"href=https://evil.example/_next/static/css/c3.css","/_next/static/css/a1.css"]]}""",
            "www.boat4you.com",
        ) shouldContainExactlyInAnyOrder setOf("/_next/static/css/a1.css", "/_next/static/css/b2.css")
    }

    @Test
    fun `archived css has its url() rewritten to replay-assets, its font stored, data and other hosts untouched`() {
        archiver.archive("boat4you", setOf("/_next/static/css/a1.css")) shouldBe 2
        val host = "127.0.0.1_${server.address.port}"
        val stored =
            jdbc.queryForMap("SELECT path, sha256 FROM rec_asset WHERE url LIKE '%a1.css'").let { root.resolve("assets").resolve(it["path"] as String) }
        val text = Files.readString(stored)
        text shouldContain "url(/replay-assets/$host/"
        text shouldContain ".woff2)"
        text shouldNotContain "/_next/static/media/f1.woff2"
        text shouldContain "url(data:image/png;base64,AAAA)"
        text shouldContain "url('https://cdn.example.com/z.png')"
        val font = jdbc.queryForObject("SELECT path FROM rec_asset WHERE url LIKE '%f1.woff2'", String::class.java)!!
        Files.readAllBytes(root.resolve("assets").resolve(font)).toList() shouldBe listOf<Byte>(1, 2, 3, 4)
    }

    @Test
    fun `a known stylesheet is fetched once, a later recording only marks it as used`() {
        archiver.archive("boat4you", setOf("/_next/static/css/a1.css"))
        archiver.archive("boat4you", setOf("/_next/static/css/a1.css", "/_next/static/css/gone.css")) shouldBe 0
        hits["/_next/static/css/a1.css"] shouldBe 1
        hits["/_next/static/media/f1.woff2"] shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM rec_asset", Long::class.java) shouldBe 2
    }

    @Test
    fun `assets no recording used for 67 days are deleted, files and rows`() {
        archiver.archive("boat4you", setOf("/_next/static/css/a1.css"))
        jdbc.update("UPDATE rec_asset SET last_used_at = now() - interval '68 days' WHERE url LIKE '%f1.woff2'")
        val font = root.resolve("assets").resolve(jdbc.queryForObject("SELECT path FROM rec_asset WHERE url LIKE '%f1.woff2'", String::class.java)!!)
        archiver.purgeUnused(67) shouldBe 1
        Files.exists(font) shouldBe false
        jdbc.queryForObject("SELECT count(*) FROM rec_asset", Long::class.java) shouldBe 1
    }
}
