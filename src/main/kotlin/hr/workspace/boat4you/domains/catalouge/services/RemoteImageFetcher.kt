package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Downloads one photo for the admin import POST /admin/locations/{id}/images/from-url (marina photos, 16.10.2026).
 * Admin-only, but the server fetches a URL somebody typed, so: http(s) only, no credentials in the URL, every address
 * of the host public (no loopback, private, link-local - cloud metadata 169.254.169.254 -, CGNAT, multicast, IPv6 ULA),
 * redirects followed by hand (at most [MAX_REDIRECTS]) with each hop checked again, [timeout] for the whole download
 * including the body, at most the upload cap (application.upload.max-file-size-mb, 10 MB), Content-Type image/ *. The
 * magic bytes (JPEG / PNG / WebP) are checked afterwards by FileSystemService.saveImage, exactly as for an upload.
 * Not covered: a host that resolves to a public address here and to a private one for the HTTP client a moment later
 * (DNS rebinding) - acceptable for a SYSTEM_ADMIN-only tool.
 */
@Component
class RemoteImageFetcher(
    @Value("\${application.upload.max-file-size-mb:10}") private val maxFileSizeMb: Long,
) {
    /** Why a URL was not imported; the message goes back to the admin in the 400 body. */
    class Rejected(
        message: String,
    ) : RuntimeException(message)

    // Test seams (the project's `internal var` pattern, as YachtImageService.resize).
    internal var resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    internal var addressAllowed: (InetAddress) -> Boolean = ::isPublicAddress
    internal var timeout: Duration = Duration.ofSeconds(15)

    private val maxBytes: Long get() = maxFileSizeMb * 1024 * 1024

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun fetch(url: String): ByteArray {
        val deadline = System.nanoTime() + timeout.toNanos()
        var uri = parse(url)
        repeat(MAX_REDIRECTS + 1) {
            checkHost(uri)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw Rejected("timed out after ${timeout.seconds} s")
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/*")
                    .GET()
                    .build()
            val future = client.sendAsync(request, HttpResponse.BodyHandler { info -> bodyFor(info) })
            val response =
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw Rejected("timed out after ${timeout.seconds} s")
                } catch (e: ExecutionException) {
                    val causes = generateSequence(e.cause) { it.cause }.toList()
                    throw causes.filterIsInstance<Rejected>().firstOrNull()
                        ?: if (causes.any { it is HttpTimeoutException }) {
                            Rejected("timed out after ${timeout.seconds} s")
                        } else {
                            Rejected("download failed (${causes.firstOrNull()?.javaClass?.simpleName})")
                        }
                }
            val status = response.statusCode()
            when {
                status in 300..399 -> {
                    val location =
                        response.headers().firstValue("location").orElse(null)
                            ?: throw Rejected("HTTP $status without a Location header")
                    uri = parse(runCatching { uri.resolve(location.trim()) }.getOrElse { throw Rejected("bad redirect $location") }.toString())
                }
                status != 200 -> throw Rejected("HTTP $status")
                !isImage(response.headers()) ->
                    throw Rejected("content type ${response.headers().firstValue("content-type").orElse("(none)")} is not an image")
                tooLong(response.headers()) -> throw Rejected("larger than $maxFileSizeMb MB")
                else -> return response.body() ?: throw Rejected("empty body")
            }
        }
        throw Rejected("more than $MAX_REDIRECTS redirects")
    }

    private fun parse(url: String): URI {
        val uri = runCatching { URI(url.trim()) }.getOrElse { throw Rejected("not a valid URL") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw Rejected("only http(s) URLs")
        if (uri.host.isNullOrBlank()) throw Rejected("URL without a host")
        if (uri.rawUserInfo != null) throw Rejected("URL with credentials")
        return uri
    }

    private fun checkHost(uri: URI) {
        val host = uri.host
        val addresses = runCatching { resolve(host) }.getOrElse { throw Rejected("host $host does not resolve") }
        if (addresses.isEmpty() || !addresses.all(addressAllowed)) throw Rejected("host $host is not a public address")
    }

    /** The body is read only for a 200 image within the cap; anything else is discarded unread past the headers. */
    private fun bodyFor(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray?> =
        if (info.statusCode() == 200 && isImage(info.headers()) && !tooLong(info.headers())) {
            CappedBody(maxBytes) { Rejected("larger than $maxFileSizeMb MB") }
        } else {
            HttpResponse.BodySubscribers.replacing(null)
        }

    private fun isImage(headers: HttpHeaders): Boolean = headers.firstValue("content-type").orElse("").trim().lowercase(Locale.ROOT).startsWith("image/")

    private fun tooLong(headers: HttpHeaders): Boolean = headers.firstValueAsLong("content-length").orElse(-1) > maxBytes

    /** Collects the body, failing (and cancelling the download) as soon as it passes [max] bytes. */
    private class CappedBody(
        private val max: Long,
        private val tooLarge: () -> Exception,
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
                if (out.size() + buffer.remaining() > max) {
                    subscription?.cancel()
                    result.completeExceptionally(tooLarge())
                    return
                }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }

    companion object {
        const val MAX_REDIRECTS = 3
        private const val USER_AGENT = "Mozilla/5.0 (compatible; Boat4You-ImageImport/1.0; +https://www.boat4you.com)"

        /** A public unicast address: not loopback, any-local, link-local, private (site-local), multicast, 0/8, CGNAT, broadcast or IPv6 ULA. */
        fun isPublicAddress(address: InetAddress): Boolean {
            val local = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
            if (local || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 0xFF }
            return when (address) {
                is Inet4Address -> b[0] != 0 && !(b[0] == 100 && b[1] in 64..127) && b.any { it != 255 }
                is Inet6Address -> (b[0] and 0xFE) != 0xFC
                else -> false
            }
        }
    }
}
