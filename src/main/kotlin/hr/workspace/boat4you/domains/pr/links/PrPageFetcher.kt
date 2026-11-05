package hr.workspace.boat4you.domains.pr.links

import hr.workspace.boat4you.domains.catalouge.services.RemoteImageFetcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * One fetch of a page for the PR link checker (and the Task 8 feeds): [status] is the HTTP status of the last hop, or -1
 * when nothing was fetched (blocked address, bad URL, too many redirects, timeout, network error). [finalUrl] is the
 * URL after redirects (relative links resolve against it). [html] is the decoded body of a 2xx answer only (at most
 * [PrPageFetcher.MAX_BYTES] bytes of it), else null. [xRobotsTag] is every X-Robots-Tag header joined with ", ".
 */
data class FetchResult(
    val status: Int,
    val finalUrl: String,
    val html: String?,
    val xRobotsTag: String?,
)

/**
 * Fetches third-party article pages for the link check. The URLs are typed in by people (admin, PR API key holder),
 * so this is an SSRF surface and mirrors RemoteImageFetcher: http(s) only, no credentials in the URL, every address
 * the host resolves to must pass [isFetchableAddress] (RemoteImageFetcher.isPublicAddress plus IPv6 forms that embed
 * a private IPv4), redirects followed by hand (at most [MAX_REDIRECTS]) with each hop checked again, [timeout] for the
 * whole fetch, at most [MAX_BYTES] of the body read (the rest is dropped, not an error). Never throws: any failure is
 * status -1. Nothing of the page goes back to the caller of the API, only the derived link state and the status.
 *
 * Not covered (same as RemoteImageFetcher): DNS rebinding, a host that resolves to a public address for the check and
 * to a private one for the HTTP client a moment later. java.net.http.HttpClient cannot be told which address to
 * connect to without breaking TLS hostname checks for https, so the check and the connect resolve separately. The
 * exposure is a blind GET (no body or headers reach the caller) from a domain that the attacker controls.
 */
@Component
class PrPageFetcher {
    private val log = LoggerFactory.getLogger(PrPageFetcher::class.java)

    // Test seams (the project's `internal var` pattern, as RemoteImageFetcher).
    internal var resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    internal var addressAllowed: (InetAddress) -> Boolean = ::isFetchableAddress
    internal var timeout: Duration = Duration.ofSeconds(15)

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private class Blocked(
        message: String,
    ) : RuntimeException(message)

    fun fetch(url: String): FetchResult {
        var current = url.trim()
        return try {
            val deadline = System.nanoTime() + timeout.toNanos()
            var uri = parse(current)
            repeat(MAX_REDIRECTS + 1) {
                current = uri.toString()
                checkHost(uri)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw Blocked("timed out")
                val request =
                    HttpRequest
                        .newBuilder(uri)
                        .timeout(Duration.ofNanos(remaining))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET()
                        .build()
                val future = client.sendAsync(request) { info -> bodyFor(info) }
                val response =
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS)
                    } catch (e: Exception) {
                        future.cancel(true)
                        throw e
                    }
                val status = response.statusCode()
                val location = response.headers().firstValue("location").orElse(null)
                if (status in REDIRECTS && location != null) {
                    // URI.resolve against "http://host" (empty path) would glue a relative Location onto the host name.
                    val base = if (uri.rawPath.isNullOrEmpty()) uri.resolve("/") else uri
                    uri = parse(base.resolve(location.trim()).toString())
                } else {
                    val robots = response.headers().allValues("x-robots-tag").ifEmpty { null }?.joinToString(", ")
                    val html = response.body()?.let { decode(it, response.headers().firstValue("content-type").orElse(null)) }
                    return FetchResult(status, current, html, robots)
                }
            }
            throw Blocked("more than $MAX_REDIRECTS redirects")
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.info("PR link check: {} not fetched ({}: {})", current.take(LOG_URL), e.javaClass.simpleName, e.message?.take(LOG_URL))
            FetchResult(-1, current, null, null)
        }
    }

    private fun parse(url: String): URI {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw Blocked("only http(s) URLs")
        if (uri.host.isNullOrBlank()) throw Blocked("URL without a host")
        if (uri.rawUserInfo != null) throw Blocked("URL with credentials")
        return uri
    }

    private fun checkHost(uri: URI) {
        val addresses = resolve(uri.host)
        if (addresses.isEmpty() || !addresses.all(addressAllowed)) throw Blocked("host ${uri.host} is not a public address")
    }

    /** Only a 2xx body is read (capped); anything else is discarded unread past the headers. */
    private fun bodyFor(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray?> =
        if (info.statusCode() in 200..299) CappedBody(MAX_BYTES) else HttpResponse.BodySubscribers.replacing(null)

    private fun decode(
        bytes: ByteArray,
        contentType: String?,
    ): String {
        val declared =
            contentType
                ?.split(';')
                ?.drop(1)
                ?.map { it.trim() }
                ?.firstOrNull { it.lowercase(Locale.ROOT).startsWith("charset=") }
                ?.substringAfter('=')
                ?.trim('"', '\'', ' ')
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** Collects the body up to [max] bytes, then cancels the download and keeps what it has. */
    private class CappedBody(
        private val max: Int,
    ) : HttpResponse.BodySubscriber<ByteArray?> {
        private val out = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray?>()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray?> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            for (buffer in item) {
                val take = minOf(buffer.remaining(), max - out.size())
                val bytes = ByteArray(take)
                buffer.get(bytes)
                out.write(bytes)
                if (out.size() >= max) {
                    subscription?.cancel()
                    result.complete(out.toByteArray())
                    return
                }
            }
        }

        override fun onError(throwable: Throwable) {
            if (!result.isDone) result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }

    companion object {
        const val MAX_REDIRECTS = 3
        const val MAX_BYTES = 3 * 1024 * 1024
        const val USER_AGENT = "Boat4youLinkCheck/1.0 (+https://www.boat4you.com/press)"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val LOG_URL = 300

        /**
         * RemoteImageFetcher.isPublicAddress, and for IPv6 also the IPv4 address some forms carry inside: IPv4-compatible
         * (::a.b.c.d), NAT64 (64:ff9b::/96) and 6to4 (2002::/16). Without this, ::127.0.0.1 or 64:ff9b::a9fe:a9fe
         * (169.254.169.254 through a NAT64 gateway) would count as public. IPv4-mapped (::ffff:a.b.c.d) already comes
         * back from InetAddress as the IPv4 address.
         */
        fun isFetchableAddress(address: InetAddress): Boolean {
            if (!RemoteImageFetcher.isPublicAddress(address)) return false
            if (address !is Inet6Address) return true
            val b = address.address
            val embedded =
                when {
                    b.take(12).all { it.toInt() == 0 } -> b.copyOfRange(12, 16)
                    b.take(12) == NAT64_PREFIX -> b.copyOfRange(12, 16)
                    b[0].toInt() == 0x20 && b[1].toInt() == 0x02 -> b.copyOfRange(2, 6)
                    else -> return true
                }
            return RemoteImageFetcher.isPublicAddress(InetAddress.getByAddress(embedded))
        }

        private val NAT64_PREFIX = listOf(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }
    }
}
