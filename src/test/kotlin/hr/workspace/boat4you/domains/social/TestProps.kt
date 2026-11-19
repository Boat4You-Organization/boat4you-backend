package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.connectors.SocialHttp
import hr.workspace.boat4you.domains.social.service.SocialProperties
import java.net.http.HttpRequest
import java.util.concurrent.Flow

object TestProps {
    @Suppress("LongParameterList") // each override a test needs
    fun props(
        tokenKey: String = "",
        metaAppId: String = "meta-app",
        metaAppSecret: String = "meta-secret",
        tiktokKey: String = "tt-key",
        tiktokSecret: String = "tt-secret",
        mediaDir: String = "/tmp/social-test",
    ) = SocialProperties(
        tokenKey = tokenKey,
        publicApiUrl = "https://api.boat4you.com",
        adminOrigins = "https://admin.boat4you.com",
        mediaDir = mediaDir,
        maxImageMb = 20,
        maxVideoMb = 500,
        metaAppId = metaAppId,
        metaAppSecret = metaAppSecret,
        metaGraphVersion = "v23.0",
        threadsAppId = "",
        threadsAppSecret = "",
        tiktokClientKey = tiktokKey,
        tiktokClientSecret = tiktokSecret,
        tiktokAudited = false,
        googleClientId = "",
        googleClientSecret = "",
        linkedinClientId = "",
        linkedinClientSecret = "",
        linkedinScopes = "openid profile w_member_social",
        linkedinVersion = "202510",
        pinterestAppId = "pin-app",
        pinterestAppSecret = "pin-secret",
        blueskyService = "https://bsky.social",
        anthropicApiKey = "",
        anthropicBaseUrl = "https://api.anthropic.com",
        aiModel = "claude-opus-5-5",
        selfBaseUrl = "http://127.0.0.1:8080",
        higgsfieldKeyId = "",
        higgsfieldKeySecret = "",
        higgsfieldBaseUrl = "https://api.higgsfield.ai",
        higgsfieldImageToVideoPath = "/wan/v2.7/image-to-video",
        higgsfieldStatusPath = "/requests/{id}/status",
    )
}

/** Records every request (method, URL, body) and answers from [routes]: first route whose key is contained in the URL. */
class FakeHttp(
    private val routes: List<Pair<String, String>>,
) : SocialHttp() {
    data class Call(
        val method: String,
        val url: String,
        val body: String,
    )

    val calls = mutableListOf<Call>()

    override fun send(request: HttpRequest): Answer {
        val url = request.uri().toString()
        calls += Call(request.method(), url, requestBody(request))
        val raw = routes.firstOrNull { (k, _) -> url.contains(k) }?.second ?: error("No fake route for ${request.method()} $url")
        return Answer(200, json.readTree(raw), mapOf("x-restli-id" to listOf("urn:li:share:1")))
    }
}

/** The body a request would send, as text (form, JSON). */
fun requestBody(request: HttpRequest): String {
    val publisher = request.bodyPublisher().orElse(null) ?: return ""
    val out = StringBuilder()
    val done = java.util.concurrent.CountDownLatch(1)
    publisher.subscribe(
        object : Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: Flow.Subscription) = s.request(Long.MAX_VALUE)

            override fun onNext(item: java.nio.ByteBuffer) {
                val b = ByteArray(item.remaining())
                item.get(b)
                out.append(String(b, Charsets.UTF_8))
            }

            override fun onError(t: Throwable) = done.countDown()

            override fun onComplete() = done.countDown()
        },
    )
    done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    return out.toString()
}
