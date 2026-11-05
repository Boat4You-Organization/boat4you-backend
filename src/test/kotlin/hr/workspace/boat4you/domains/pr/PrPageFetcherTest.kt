package hr.workspace.boat4you.domains.pr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The link checker's fetcher against a local stub HTTP server on 127.0.0.1 (never the internet). By default the
 * fetcher refuses loopback, so a test that wants to reach the stub declares 127.0.0.1 "public" through the
 * addressAllowed seam; the SSRF tests use the real guard and prove the stub was never hit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrPageFetcherTest {
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val userAgents = ConcurrentHashMap<String, String>()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/") { ex -> handle(ex) }
            executor = Executors.newCachedThreadPool()
            start()
        }

    private val base = "http://127.0.0.1:${server.address.port}"

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        userAgents[path] = ex.requestHeaders.getFirst("User-Agent") ?: ""
        when (path) {
            "/article" -> send(ex, 200, """<a href="https://www.boat4you.com/">b</a>""", "text/html; charset=utf-8", "X-Robots-Tag" to "noarchive")
            "/cp1250" -> send(ex, 200, "<p>Šibenik čarter</p>".toByteArray(charset("windows-1250")), "text/html; charset=windows-1250")
            "/no-charset" -> send(ex, 200, "<p>Šibenik</p>".toByteArray(), "text/html")
            "/big" -> send(ex, 200, ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() }, "text/html")
            "/gone" -> send(ex, 410, "gone", "text/html")
            "/forbidden" -> send(ex, 403, "no bots", "text/html")
            "/to-relative" -> redirect(ex, "/article")
            "/to-metadata" -> redirect(ex, "http://169.254.169.254/latest/meta-data/")
            "/to-ipv6-loopback" -> redirect(ex, "http://[::1]:${server.address.port}/secret")
            "/to-file" -> redirect(ex, "file:///etc/passwd")
            "/to-localhost" -> redirect(ex, "http://localhost:${server.address.port}/secret")
            "/loop" -> redirect(ex, "/loop")
            "/r1" -> redirect(ex, "/r2")
            "/r2" -> redirect(ex, "/r3")
            "/r3" -> redirect(ex, "/article")
            "/slow" -> {
                Thread.sleep(2_000)
                send(ex, 200, "late", "text/html")
            }
            else -> send(ex, 404, "nope", "text/html")
        }
    }

    private fun send(
        ex: HttpExchange,
        status: Int,
        body: String,
        type: String,
        vararg headers: Pair<String, String>,
    ) = send(ex, status, body.toByteArray(), type, *headers)

    private fun send(
        ex: HttpExchange,
        status: Int,
        body: ByteArray,
        type: String,
        vararg headers: Pair<String, String>,
    ) {
        ex.responseHeaders.add("Content-Type", type)
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, body.size.toLong())
        runCatching { ex.responseBody.use { it.write(body) } }
    }

    private fun redirect(
        ex: HttpExchange,
        location: String,
    ) {
        ex.responseHeaders.add("Location", location)
        ex.sendResponseHeaders(302, -1)
        ex.close()
    }

    @AfterAll
    fun stop() = server.stop(0)

    @BeforeEach
    fun clear() {
        hits.clear()
        userAgents.clear()
    }

    private fun hits(path: String) = hits[path]?.get() ?: 0

    /**
     * Resolution without the network: IP literals are parsed by the JDK (no lookup), "localhost" is the loopback pair,
     * any other name fails as an unknown host would. So no test here sends a DNS query, whatever the URL.
     */
    private val offlineResolve: (String) -> List<InetAddress> = { host ->
        when {
            host == "localhost" -> listOf(loopback, InetAddress.getByName("::1"))
            LITERAL.matches(host) -> InetAddress.getAllByName(host).toList()
            else -> throw UnknownHostException(host)
        }
    }

    /** The real guard, offline resolution. */
    private fun guardedFetcher() = PrPageFetcher().apply { resolve = offlineResolve }

    /** A fetcher that treats only the stub (127.0.0.1) as public, plus every really public address; offline resolution. */
    private fun stubFetcher() =
        PrPageFetcher().apply {
            resolve = offlineResolve
            addressAllowed = { it == loopback || PrPageFetcher.isFetchableAddress(it) }
        }

    @Test
    fun privateAddressNeverFetched() {
        val fetcher = guardedFetcher()
        val port = server.address.port
        listOf(
            "http://169.254.169.254/",
            "http://169.254.169.254/latest/meta-data/iam/",
            "$base/article",
            "http://localhost:$port/article",
            "http://[::1]:$port/article",
            "http://[::ffff:127.0.0.1]:$port/article",
            "http://[::127.0.0.1]:$port/article",
            "http://2130706433:$port/article",
            "http://0x7f000001:$port/article",
            "http://127.1:$port/article",
            "http://www.boat4you.com@127.0.0.1:$port/article",
            "http://10.0.0.1/",
            "http://192.168.1.1/",
            "http://100.64.0.1/",
            "http://[fd00::1]/",
            "http://0.0.0.0:$port/article",
        ).forEach { url ->
            val result = fetcher.fetch(url)
            result.status shouldBe -1
            result.html shouldBe null
        }
        hits("/article") shouldBe 0
    }

    @Test
    fun leadingZeroOctetsAreDecimalNotOctal() {
        // The JDK reads 0177.0.0.1 as 177.0.0.1 (public), not as octal 127.0.0.1, so the check and the connect agree and
        // the "octal loopback" trick cannot reach this machine. Asserted on the parse only: fetching it would go out.
        InetAddress.getByName("0177.0.0.1") shouldBe InetAddress.getByName("177.0.0.1")
        PrPageFetcher.isFetchableAddress(InetAddress.getByName("0177.0.0.1")) shouldBe true
    }

    @Test
    fun ipv6FormsThatEmbedAPrivateIpv4AreRefused() {
        listOf("::127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe", "2002:7f00:1::", "2002:a00:1::1", "::", "::1", "fe80::1", "fc00::1")
            .forEach { PrPageFetcher.isFetchableAddress(InetAddress.getByName(it)) shouldBe false }
        listOf("8.8.8.8", "2a00:1450:4001::200e", "2002:808:808::1", "64:ff9b::808:808")
            .forEach { PrPageFetcher.isFetchableAddress(InetAddress.getByName(it)) shouldBe true }
    }

    @Test
    fun onlyHttpAndHttpsAreFetched() {
        val fetcher = stubFetcher()
        listOf("file:///etc/passwd", "ftp://127.0.0.1/x", "gopher://127.0.0.1:70/_x", "jar:http://127.0.0.1/!/", "not a url", "", "//127.0.0.1/article")
            .forEach { fetcher.fetch(it).status shouldBe -1 }
        // Credentials in the URL are refused even for an allowed host.
        fetcher.fetch("http://user:pw@127.0.0.1:${server.address.port}/article").status shouldBe -1
        hits("/article") shouldBe 0
    }

    @Test
    fun everyResolvedAddressMustBePublic() {
        val fetcher = stubFetcher().apply { resolve = { listOf(loopback, InetAddress.getByName("10.0.0.7")) } }

        fetcher.fetch("$base/article").status shouldBe -1
        hits("/article") shouldBe 0
    }

    @Test
    fun redirectToPrivateAddressIsBlocked() {
        // "localhost" really connects to the stub; the check sees it resolve to a private address, so the hop never happens.
        val fetcher =
            stubFetcher().apply {
                resolve = { host -> if (host == "localhost") listOf(InetAddress.getByName("10.1.2.3")) else offlineResolve(host) }
            }
        fetcher.fetch("$base/to-localhost").status shouldBe -1
        hits("/to-localhost") shouldBe 1

        fetcher.fetch("$base/to-metadata").status shouldBe -1
        fetcher.fetch("$base/to-ipv6-loopback").status shouldBe -1
        fetcher.fetch("$base/to-file").status shouldBe -1
        hits("/secret") shouldBe 0
        hits("/to-metadata") shouldBe 1
    }

    @Test
    fun followsAtMostThreeRedirects() {
        val fetcher = stubFetcher()

        val relative = fetcher.fetch("$base/to-relative")
        relative.status shouldBe 200
        relative.finalUrl shouldBe "$base/article"

        val three = fetcher.fetch("$base/r1")
        three.status shouldBe 200
        three.finalUrl shouldBe "$base/article"

        fetcher.fetch("$base/loop").status shouldBe -1
        hits("/loop") shouldBe 4
    }

    @Test
    fun readsBodyHeadersAndSendsOurUserAgent() {
        val result = stubFetcher().fetch("$base/article")

        result.status shouldBe 200
        result.finalUrl shouldBe "$base/article"
        result.html shouldBe """<a href="https://www.boat4you.com/">b</a>"""
        result.xRobotsTag shouldBe "noarchive"
        userAgents["/article"] shouldBe "Boat4youLinkCheck/1.0 (+https://www.boat4you.com/press)"
    }

    @Test
    fun decodesTheDeclaredCharsetElseUtf8() {
        stubFetcher().fetch("$base/cp1250").html shouldBe "<p>Šibenik čarter</p>"
        stubFetcher().fetch("$base/no-charset").html shouldBe "<p>Šibenik</p>"
    }

    @Test
    fun errorStatusesComeBackWithoutBody() {
        val gone = stubFetcher().fetch("$base/gone")
        gone.status shouldBe 410
        gone.html shouldBe null
        stubFetcher().fetch("$base/forbidden").status shouldBe 403
        stubFetcher().fetch("$base/missing").status shouldBe 404
    }

    @Test
    fun bodyIsCappedAtThreeMegabytesWithoutFailing() {
        val result = stubFetcher().fetch("$base/big")

        result.status shouldBe 200
        result.html!!.length shouldBeGreaterThan 0
        result.html!!.length shouldBeLessThanOrEqual PrPageFetcher.MAX_BYTES
        result.html!! shouldStartWith "aaaa"
    }

    @Test
    fun timeoutIsMinusOne() {
        val fetcher = stubFetcher().apply { timeout = Duration.ofMillis(300) }

        fetcher.fetch("$base/slow").status shouldBe -1
    }

    private companion object {
        /** Dotted / plain decimal IPv4 (the JDK parses 127.1 and 2130706433 too) or a bracketed IPv6 literal. */
        val LITERAL = Regex("""^(\d+(\.\d+){0,3}|\[[0-9a-fA-F:.%]+])$""")
    }
}
