package hr.workspace.boat4you.common.services

import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.http.HttpClient
import java.time.Duration

/** A downloaded picture as the partner sent it, and the URL it came from after redirects. */
class DownloadedImage(
    val bytes: ByteArray,
    val finalUrl: String,
)

@Service
class ImageService {
    // Redirects are followed by hand (downloadImage), never by the client, so the final URL is known: a partner may
    // redirect a picture to a generic placeholder (NauSys: 307 to GenericImage/ORIGINAL/blockedPhoto.png, 16.10.2026).
    private val restClient: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient
                        .newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build(),
                ),
            ).build()

    /** The partner's bytes and the final URL. Result.failure = the download failed. */
    fun download(url: String): Result<DownloadedImage> {
        return try {
            val image =
                try {
                    // Use Spring's RestClient to download the image
                    downloadImage(url, MAX_REDIRECTS)
                } catch (e: Exception) {
                    // In some cases Spring's RestClient fails for s3 images with space in URL (NauSYS), thats why we use raw URL connection
                    fetchUsingUrlConnection(url)
                }
            Result.success(image)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun fetchUsingUrlConnection(url: String): DownloadedImage {
        try {
            // Use raw URL connection to bypass Spring's URL handling
            val urlObj = URI.create(toSafeUrl(url)).toURL()
            val connection = urlObj.openConnection() as HttpURLConnection

            // Set browser headers manually
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Connection", "keep-alive")

            connection.setRequestMethod("GET")
            connection.setConnectTimeout(30000)
            connection.setReadTimeout(30000)

            val responseCode = connection.getResponseCode()
            if (responseCode == 200) {
                connection.getInputStream().use { inputStream ->
                    // HttpURLConnection follows same-protocol redirects itself; getURL() is where it ended up
                    return DownloadedImage(inputStream.readAllBytes(), connection.url.toString())
                }
            } else {
                throw java.lang.RuntimeException("HTTP " + responseCode + ": " + connection.getResponseMessage())
            }
        } catch (e: java.lang.Exception) {
            throw java.lang.RuntimeException("URL connection failed: " + url, e)
        }
    }

    private fun downloadImage(
        url: String,
        redirectsLeft: Int,
    ): DownloadedImage {
        return try {
            val response: ResponseEntity<Resource> =
                restClient
                    .get()
                    .uri(toSafeUrl(url))
                    .retrieve()
                    .onStatus({ status -> status.is3xxRedirection }) { _, _ ->
                        // a redirect is not an error: followed below
                    }.toEntity(Resource::class.java)

            if (response.statusCode.is2xxSuccessful && response.body != null) {
                response.body!!.inputStream.use { DownloadedImage(it.readAllBytes(), url) }
            } else if (response.statusCode.is3xxRedirection) {
                downloadImage(redirectTarget(url, response.headers.location, redirectsLeft), redirectsLeft - 1)
            } else {
                throw RuntimeException("Failed to download image from URL: $url, status: ${response.statusCode}")
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to download image from URL: $url", e)
        }
    }

    /** Where a redirect from [url] points; a relative Location resolves against [url]. */
    private fun redirectTarget(
        url: String,
        location: URI?,
        redirectsLeft: Int,
    ): String {
        checkNotNull(location) { "Redirect response without location header from URL: $url" }
        check(redirectsLeft > 0) { "Too many redirects from URL: $url" }
        return if (location.isAbsolute) location.toString() else URI.create(toSafeUrl(url)).resolve(location).toString()
    }

    private fun toSafeUrl(url: String): String {
        return url
            .replace(" ", "%20")
            .replace("!", "%21")
            .replace("@", "%40")
            .replace("#", "%23")
            .replace("$", "%24")
            .replace("&", "%26")
    }

    companion object {
        private const val MAX_REDIRECTS = 5
    }
}
