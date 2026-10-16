package hr.workspace.boat4you.common.services

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.catalouge.utils.KnownPlaceholderImages
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress

/**
 * The partner picture download follows redirects itself and knows where it ended: NauSys answers some model pictures
 * with 307 to GenericImage/ORIGINAL/blockedPhoto.png (owner incident 16.10.2026) - the final URL alone marks the
 * placeholder. A local HTTP server stands in for the partner.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageServiceRedirectTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val photo = byteArrayOf(1, 2, 3, 4)
    private val placeholder = byteArrayOf(9, 9, 9)

    @BeforeAll
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg") { redirect(it, 307, "$base/nausys/GenericImage/ORIGINAL/blockedPhoto.png") }
        server.createContext("/nausys/GenericImage/ORIGINAL/blockedPhoto.png") { ok(it, placeholder) }
        server.createContext("/relative") { redirect(it, 302, "/pictures/n2.jpg") }
        server.createContext("/pictures/n2.jpg") { ok(it, photo) }
        server.createContext("/loop") { redirect(it, 302, "/loop") }
        server.start()
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun redirect(
        exchange: HttpExchange,
        status: Int,
        location: String,
    ) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun ok(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Test
    fun `a redirect to the blocked photo - the bytes and the final URL, which marks the placeholder`() {
        val image = ImageService().download("$base/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg").getOrThrow()

        image.bytes.toList() shouldBe placeholder.toList()
        image.finalUrl shouldEndWith "/nausys/GenericImage/ORIGINAL/blockedPhoto.png"
        KnownPlaceholderImages.isPlaceholder(image.finalUrl, image.bytes) shouldBe true
    }

    @Test
    fun `a relative Location resolves against the URL that answered`() {
        val image = ImageService().download("$base/relative").getOrThrow()

        image.bytes.toList() shouldBe photo.toList()
        image.finalUrl shouldBe "$base/pictures/n2.jpg"
        KnownPlaceholderImages.isPlaceholder(image.finalUrl, image.bytes) shouldBe false
    }

    @Test
    fun `a redirect loop fails instead of recursing for ever`() {
        ImageService().download("$base/loop").isFailure shouldBe true
    }
}
