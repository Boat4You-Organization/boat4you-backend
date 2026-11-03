package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.connectors.MetaConnector
import hr.workspace.boat4you.domains.social.connectors.PublishJob
import hr.workspace.boat4you.domains.social.connectors.PublishMedia
import hr.workspace.boat4you.domains.social.connectors.TikTokConnector
import hr.workspace.boat4you.domains.social.connectors.Tokens
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/** Connectors against a fake HTTP layer: which calls they make, in which order, with which fields. */
class SocialConnectorTest {
    private fun photo(n: Int) =
        PublishMedia(SocialMediaKind.IMAGE, "https://api.boat4you.com/public/social/media/tok$n.jpg", Path.of("/tmp/x$n.jpg"), "image/jpeg", 100, 1600, 1200) {
            ByteArray(0)
        }

    private fun job(
        platform: SocialPlatform,
        media: List<PublishMedia>,
        meta: Map<String, String> = emptyMap(),
    ) = PublishJob(platform, "123", Tokens("page-token", null, null), meta, false, "Kornati by catamaran", null, "https://www.boat4you.com/en", media)

    @Test
    fun `Instagram single photo - container, wait FINISHED, publish, permalink`() {
        val http =
            FakeHttp(
                listOf(
                    "/17841/media_publish" to """{"id":"IGMEDIA"}""",
                    "/17841/media" to """{"id":"CONTAINER"}""",
                    "/CONTAINER?" to """{"status_code":"FINISHED"}""",
                    "/IGMEDIA?" to """{"permalink":"https://www.instagram.com/p/abc/"}""",
                ),
            )
        val meta = MetaConnector(TestProps.props()).also { it.http = http }
        val result = meta.publish(job(SocialPlatform.INSTAGRAM, listOf(photo(1)), mapOf("igUserId" to "17841")))
        result.externalId shouldBe "IGMEDIA"
        result.url shouldBe "https://www.instagram.com/p/abc/"
        http.calls.map { it.method + " " + it.url.substringBefore('?').substringAfter("v23.0") } shouldBe
            listOf("POST /17841/media", "GET /CONTAINER", "POST /17841/media_publish", "GET /IGMEDIA")
        http.calls[0].body shouldContain "image_url=https%3A%2F%2Fapi.boat4you.com%2Fpublic%2Fsocial%2Fmedia%2Ftok1.jpg"
        http.calls[0].body shouldContain "caption=Kornati+by+catamaran"
    }

    @Test
    fun `Facebook several photos - unpublished photos, then one feed post with attached_media`() {
        var n = 0
        val http =
            object : SocialHttpRoutes() {
                override fun answer(url: String): String =
                    when {
                        url.contains("/123/photos") -> """{"id":"P${++n}"}"""
                        url.contains("/123/feed") -> """{"id":"123_999"}"""
                        else -> error(url)
                    }
            }
        val meta = MetaConnector(TestProps.props()).also { it.http = http }
        val result = meta.publish(job(SocialPlatform.FACEBOOK, listOf(photo(1), photo(2))))
        result.url shouldBe "https://www.facebook.com/123_999"
        http.calls.size shouldBe 3
        http.calls[0].body shouldContain "published=false"
        http.calls[2].body shouldContain "attached_media%5B0%5D=%7B%22media_fbid%22%3A%22P1%22%7D"
        http.calls[2].body shouldContain "attached_media%5B1%5D=%7B%22media_fbid%22%3A%22P2%22%7D"
        // A photo post has no link preview: the link rides at the end of the message.
        http.calls[2].body shouldContain "https%3A%2F%2Fwww.boat4you.com%2Fen"
    }

    @Test
    fun `Meta login - long-lived token, every page and its Instagram become channels`() {
        val http =
            FakeHttp(
                listOf(
                    "fb_exchange_token" to """{"access_token":"LONG"}""",
                    "/oauth/access_token" to """{"access_token":"SHORT"}""",
                    "/me/accounts" to
                        """{"data":[
                          {"id":"1","name":"Catamaran Charter Greece","access_token":"PT1","link":"https://facebook.com/ccg",
                           "instagram_business_account":{"id":"17841","username":"catamaran_greece"}},
                          {"id":"2","name":"Boat4You","access_token":"PT2"}]}""",
                ),
            )
        val accounts = MetaConnector(TestProps.props()).also { it.http = http }.exchange("CODE", null)
        accounts.map { it.platform to it.externalId } shouldBe
            listOf(SocialPlatform.FACEBOOK to "1", SocialPlatform.INSTAGRAM to "17841", SocialPlatform.FACEBOOK to "2")
        accounts[1].tokens.accessToken shouldBe "PT1"
        accounts[1].handle shouldBe "@catamaran_greece"
        http.calls.first().url shouldContain "code=CODE"
    }

    @Test
    fun `TikTok refreshes a token that runs out within 10 minutes, keeps a fresh one`() {
        val http = FakeHttp(listOf("/v2/oauth/token/" to """{"access_token":"NEW","refresh_token":"R2","expires_in":86400}"""))
        val tiktok = TikTokConnector(TestProps.props()).also { it.http = http }
        tiktok.refresh(Tokens("OLD", "R1", Instant.now().plusSeconds(3600))) shouldBe null
        val fresh = tiktok.refresh(Tokens("OLD", "R1", Instant.now().plusSeconds(60)))!!
        fresh.accessToken shouldBe "NEW"
        fresh.refreshToken shouldBe "R2"
        http.calls.single().body shouldContain "grant_type=refresh_token"
    }
}

/** A FakeHttp whose answer is computed per URL. */
abstract class SocialHttpRoutes : hr.workspace.boat4you.domains.social.connectors.SocialHttp() {
    val calls = mutableListOf<FakeHttp.Call>()

    abstract fun answer(url: String): String

    override fun send(request: java.net.http.HttpRequest): Answer {
        val url = request.uri().toString()
        calls += FakeHttp.Call(request.method(), url, requestBody(request))
        return Answer(200, json.readTree(answer(url)), emptyMap())
    }
}
