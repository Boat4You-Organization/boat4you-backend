package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.connectors.MetaConnector
import hr.workspace.boat4you.domains.social.connectors.PinterestConnector
import hr.workspace.boat4you.domains.social.connectors.PublishJob
import hr.workspace.boat4you.domains.social.connectors.PublishMedia
import hr.workspace.boat4you.domains.social.connectors.Tokens
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialReelMaker
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Reels: our own video from photos (ffmpeg), Facebook's Reels calls, and Pinterest pins, against a fake HTTP layer. */
class SocialReelTest {
    private fun video(
        width: Int,
        height: Int,
        seconds: Int?,
    ) = PublishMedia(SocialMediaKind.VIDEO, "https://api.boat4you.com/public/social/media/v.mp4", Path.of("/tmp/v.mp4"), "video/mp4", 1000, width, height, seconds) {
        ByteArray(0)
    }

    private fun job(
        platform: SocialPlatform,
        media: List<PublishMedia>,
        meta: Map<String, String> = emptyMap(),
        title: String? = null,
    ) = PublishJob(platform, "123", Tokens("page-token", "refresh", null), meta, false, "Kornati by catamaran", title, "https://www.boat4you.com/en", media)

    @Test
    fun `Facebook vertical short video goes out as a Reel - start, hand over our URL, finish published`() {
        val http =
            FakeHttp(
                listOf(
                    "rupload.facebook.com" to """{"success":true}""",
                    "/123/video_reels" to """{"video_id":"777","upload_url":"https://rupload.facebook.com/video-upload/v23.0/777","success":true}""",
                ),
            )
        val result = MetaConnector(TestProps.props()).also { it.http = http }.publish(job(SocialPlatform.FACEBOOK, listOf(video(1080, 1920, 12))))
        result.externalId shouldBe "777"
        result.url shouldBe "https://www.facebook.com/reel/777"
        http.calls.map { it.url.substringAfter("//").substringBefore('?') } shouldBe
            listOf("graph.facebook.com/v23.0/123/video_reels", "rupload.facebook.com/video-upload/v23.0/777", "graph.facebook.com/v23.0/123/video_reels")
        http.calls[0].body shouldContain "upload_phase=start"
        http.calls[2].body shouldContain "upload_phase=finish"
        http.calls[2].body shouldContain "video_state=PUBLISHED"
        http.calls[2].body shouldContain "video_id=777"
    }

    @Test
    fun `Facebook landscape, long or unmeasured video stays a normal page video`() {
        listOf(video(1920, 1080, 12), video(1080, 1920, 200), video(1080, 1920, null)).forEach { v ->
            val http = FakeHttp(listOf("/123/videos" to """{"id":"55"}"""))
            val result = MetaConnector(TestProps.props()).also { it.http = http }.publish(job(SocialPlatform.FACEBOOK, listOf(v)))
            result.url shouldBe "https://www.facebook.com/123/videos/55"
            http.calls.size shouldBe 1
        }
    }

    @Test
    fun `Pinterest login - every board becomes a channel with the shared tokens`() {
        val http =
            FakeHttp(
                listOf(
                    "/oauth/token" to """{"access_token":"pina_1","refresh_token":"pinr_1","expires_in":2592000}""",
                    "/user_account" to """{"username":"boat4you","profile_image":"https://i.pinimg.com/a.jpg"}""",
                    "/boards" to """{"items":[{"id":"900","name":"Boat4You"},{"id":"901","name":"Catamaran Charter Greece"}]}""",
                ),
            )
        val accounts = PinterestConnector(TestProps.props()).also { it.http = http }.exchange("code", null)
        accounts.map { it.externalId to it.name } shouldBe listOf("900" to "Boat4You", "901" to "Catamaran Charter Greece")
        accounts.all { it.tokens.accessToken == "pina_1" && it.tokens.refreshToken == "pinr_1" } shouldBe true
        accounts[0].handle shouldBe "pinterest.com/boat4you"
        http.calls[0].body shouldContain "grant_type=authorization_code"
    }

    @Test
    fun `Pinterest pin - board, title, description, the boat's link and our photo URL`() {
        val http = FakeHttp(listOf("/v5/pins" to """{"id":"42"}"""))
        val photo =
            PublishMedia(SocialMediaKind.IMAGE, "https://api.boat4you.com/public/social/media/tok1.jpg", Path.of("/tmp/x1.jpg"), "image/jpeg", 100, 1600, 1200) {
                ByteArray(0)
            }
        val result =
            PinterestConnector(TestProps.props())
                .also { it.http = http }
                .publish(job(SocialPlatform.PINTEREST, listOf(photo), mapOf("boardId" to "900"), title = "Lagoon 46 in Kornati"))
        result.url shouldBe "https://www.pinterest.com/pin/42/"
        val body = http.calls.single().body
        body shouldContain """"board_id":"900""""
        body shouldContain """"title":"Lagoon 46 in Kornati""""
        body shouldContain """"link":"https://www.boat4you.com/en""""
        body shouldContain """"source_type":"image_url""""
    }

    @Test
    fun `Pinterest refreshes a token close to its end and keeps the refresh token it had`() {
        val http = FakeHttp(listOf("/oauth/token" to """{"access_token":"pina_2","expires_in":2592000}"""))
        val pinterest = PinterestConnector(TestProps.props()).also { it.http = http }
        pinterest.refresh(Tokens("pina_1", "pinr_1", Instant.now().plusSeconds(20L * 86_400))) shouldBe null
        val fresh = pinterest.refresh(Tokens("pina_1", "pinr_1", Instant.now().plusSeconds(86_400)))!!
        fresh.accessToken shouldBe "pina_2"
        fresh.refreshToken shouldBe "pinr_1"
        http.calls.single().body shouldContain "grant_type=refresh_token"
    }

    @Test
    fun `reel command - one input per photo, cross-fades between them, vertical H264 with a sound track`() {
        val cmd = SocialReelMaker.command(listOf(Path.of("/m/a.jpg"), Path.of("/m/b.jpg"), Path.of("/m/c.jpg")), Path.of("/tmp/out.mp4"))
        cmd.count { it == "-i" } shouldBe 4
        val graph = cmd[cmd.indexOf("-filter_complex") + 1]
        Regex("xfade").findAll(graph).count() shouldBe 2
        graph shouldContain "s=1080x1920"
        cmd shouldContain "libx264"
        cmd shouldContain "aac"
        SocialReelMaker.duration(3) shouldBe (8.4 plusOrMinus 0.001)
    }

    @Test
    fun `reel command makes a real vertical video when ffmpeg is installed`() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false), "ffmpeg not installed")
        val dir = Files.createTempDirectory("reel-test")
        val photos =
            listOf(Color.BLUE, Color.ORANGE).mapIndexed { i, c ->
                val img = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
                img.createGraphics().apply {
                    color = c
                    fillRect(0, 0, 1200, 800)
                    dispose()
                }
                dir.resolve("p$i.jpg").also { ImageIO.write(img, "jpg", it.toFile()) }
            }
        val out = dir.resolve("out.mp4")
        val p = ProcessBuilder(SocialReelMaker.command(photos, out)).redirectErrorStream(true).redirectOutput(dir.resolve("log.txt").toFile()).start()
        p.waitFor(120, TimeUnit.SECONDS) shouldBe true
        p.exitValue() shouldBe 0
        val probe =
            ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", out.toString())
                .redirectErrorStream(true)
                .start()
        probe.inputStream.bufferedReader().readText().trim() shouldBe "1080,1920"
    }
}
