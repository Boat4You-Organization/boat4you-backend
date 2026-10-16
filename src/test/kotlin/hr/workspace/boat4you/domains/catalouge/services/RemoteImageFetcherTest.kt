package hr.workspace.boat4you.domains.catalouge.services

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration

/**
 * The server-side download of the admin's marina-photo import (16.10.2026): http(s) only, public hosts only (each
 * redirect hop again), 15 s for the whole download, the 10 MB cap (header or streamed), Content-Type image/ *.
 * A local HTTP server stands in for the marina site; its loopback address is allowed through the test seam only.
 */
class RemoteImageFetcherTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32)

    private val fetcher =
        RemoteImageFetcher(maxFileSizeMb = 1).apply {
            addressAllowed = { it.isLoopbackAddress }
            timeout = Duration.ofSeconds(3)
        }

    private fun HttpExchange.reply(
        status: Int,
        body: ByteArray = ByteArray(0),
        contentType: String? = "image/webp",
        chunked: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ) {
        contentType?.let { responseHeaders.add("Content-Type", it) }
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val length =
            when {
                chunked -> 0L
                body.isEmpty() -> -1L
                else -> body.size.toLong()
            }
        sendResponseHeaders(status, length)
        responseBody.use { it.write(body) }
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/photo.webp") { it.reply(200, webp) }
        server.createContext("/page.html") { it.reply(200, "<html></html>".toByteArray(), contentType = "text/html; charset=utf-8") }
        server.createContext("/missing.jpg") { it.reply(404, contentType = "text/html") }
        server.createContext("/big.jpg") { it.reply(200, ByteArray(1024 * 1024 + 1), contentType = "image/jpeg") }
        server.createContext("/big-chunked.jpg") { it.reply(200, ByteArray(1024 * 1024 + 1), contentType = "image/jpeg", chunked = true) }
        server.createContext("/moved") { it.reply(301, contentType = null, headers = mapOf("Location" to "/photo.webp")) }
        server.createContext("/loop") { it.reply(302, contentType = null, headers = mapOf("Location" to "/loop")) }
        server.createContext("/to-private") { it.reply(302, contentType = null, headers = mapOf("Location" to "http://10.0.0.1/photo.jpg")) }
        server.createContext("/slow.jpg") {
            Thread.sleep(5_000)
            it.reply(200, webp)
        }
        server.start()
        base = "http://${server.address.address.hostAddress}:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun rejected(url: String) = shouldThrow<RemoteImageFetcher.Rejected> { fetcher.fetch(url) }.message!!

    @Test
    fun `an image is downloaded, a redirect followed`() {
        fetcher.fetch("$base/photo.webp") shouldBe webp
        fetcher.fetch("$base/moved") shouldBe webp
    }

    @Test
    fun `a page that is not an image, an error status, a redirect loop are rejected`() {
        rejected("$base/page.html") shouldContain "content type text/html; charset=utf-8 is not an image"
        rejected("$base/missing.jpg") shouldBe "HTTP 404"
        rejected("$base/loop") shouldBe "more than 3 redirects"
    }

    @Test
    fun `over the cap - by Content-Length and streamed without one`() {
        rejected("$base/big.jpg") shouldBe "larger than 1 MB"
        rejected("$base/big-chunked.jpg") shouldBe "larger than 1 MB"
    }

    @Test
    fun `the whole download is bounded by the timeout`() {
        rejected("$base/slow.jpg") shouldContain "timed out"
    }

    @Test
    fun `bad URLs and hosts - scheme, credentials, private address, a redirect to a private host`() {
        rejected("ftp://example.com/a.jpg") shouldBe "only http(s) URLs"
        rejected("file:///etc/passwd") shouldBe "only http(s) URLs"
        rejected("javascript:alert(1)") shouldBe "only http(s) URLs"
        rejected("https://user:secret@example.com/a.jpg") shouldBe "URL with credentials"
        rejected("http://exa mple.com/a.jpg") shouldBe "not a valid URL"
        rejected("$base/to-private") shouldBe "host 10.0.0.1 is not a public address"
        // the production rule: loopback (the test server itself) and the cloud metadata address are refused
        fetcher.addressAllowed = { RemoteImageFetcher.isPublicAddress(it) }
        rejected("$base/photo.webp") shouldContain "is not a public address"
        rejected("http://169.254.169.254/latest/meta-data/") shouldBe "host 169.254.169.254 is not a public address"
    }

    @Test
    fun `a host that does not resolve`() {
        fetcher.resolve = { throw java.net.UnknownHostException(it) }
        rejected("https://no-such-marina.invalid/a.jpg") shouldBe "host no-such-marina.invalid does not resolve"
    }

    @Test
    fun `public addresses only`() {
        "127.0.0.1 0.0.0.0 0.1.2.3 10.1.2.3 172.16.0.1 192.168.1.112 169.254.169.254 100.64.0.1 224.0.0.1 255.255.255.255 ::1 fe80::1 fd00::1 ::ffff:127.0.0.1"
            .split(" ")
            .forEach { RemoteImageFetcher.isPublicAddress(InetAddress.getByName(it)) shouldBe false }
        "8.8.8.8 185.199.108.153 100.128.0.1 2a00:1450:4001:80b::200e"
            .split(" ")
            .forEach { RemoteImageFetcher.isPublicAddress(InetAddress.getByName(it)) shouldBe true }
    }
}
