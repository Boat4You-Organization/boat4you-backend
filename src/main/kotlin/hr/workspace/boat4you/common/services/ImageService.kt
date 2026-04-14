package hr.workspace.boat4you.common.services

import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

@Service
class ImageService {
    private val restClient: RestClient =
        RestClient
            .builder()
            .build()

    fun downloadAsWebp(url: String): Result<ByteArray?> {
        return try {
            val inputStream =
                try {
                    // Use Spring's RestClient to download the image
                    downloadImage(url)
                } catch (e: Exception) {
                    // In some cases Spring's RestClient fails for s3 images with space in URL (NauSYS), thats why we use raw URL connection
                    ByteArrayInputStream(fetchUsingUrlConnection(url))
                }
            Result.success(ImageUtils.convertToWebP(inputStream))
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun fetchUsingUrlConnection(url: String): ByteArray? {
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
                    return inputStream.readAllBytes()
                }
            } else {
                throw java.lang.RuntimeException("HTTP " + responseCode + ": " + connection.getResponseMessage())
            }
        } catch (e: java.lang.Exception) {
            throw java.lang.RuntimeException("URL connection failed: " + url, e)
        }
    }

    private fun downloadImage(url: String): InputStream {
        return try {
            val response: ResponseEntity<Resource> =
                restClient
                    .get()
                    .uri(toSafeUrl(url))
                    .retrieve()
                    .onStatus({ status -> status.is3xxRedirection }) { _, _ ->
                        // Let Spring handle redirects automatically
                        // This block can be used for custom redirect logic if needed
                    }.toEntity(Resource::class.java)

            if (response.statusCode.is2xxSuccessful && response.body != null) {
                response.body!!.inputStream
            } else if (response.statusCode.is3xxRedirection) {
                // Handle case where redirect wasn't followed automatically
                val location = response.headers.location?.toString()
                if (location != null) {
                    // Recursive call to follow redirect
                    downloadImage(location)
                } else {
                    throw RuntimeException("Redirect response without location header from URL: $url")
                }
            } else {
                throw RuntimeException("Failed to download image from URL: $url, status: ${response.statusCode}")
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to download image from URL: $url", e)
        }
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
}
