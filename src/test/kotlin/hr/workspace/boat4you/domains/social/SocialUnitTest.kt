package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.social.ai.HiggsfieldClient
import hr.workspace.boat4you.domains.social.ai.SocialBoatPicker
import hr.workspace.boat4you.domains.social.connectors.BlueskyConnector
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostStatus
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import hr.workspace.boat4you.domains.social.service.SiteGuess
import hr.workspace.boat4you.domains.social.service.SocialAccountService
import hr.workspace.boat4you.domains.social.service.SocialConfigException
import hr.workspace.boat4you.domains.social.service.SocialCrypto
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import hr.workspace.boat4you.domains.social.service.SocialPostService
import hr.workspace.boat4you.domains.social.service.SocialRules
import hr.workspace.boat4you.domains.social.service.SocialRules.MediaInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Base64
import kotlin.random.Random

/** Objave (S1): the parts with no database or network. */
class SocialUnitTest {
    @Nested
    inner class Crypto {
        private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        @Test
        fun `a token round-trips and never appears in the stored value`() {
            val c = SocialCrypto(TestProps.props(tokenKey = key))
            val stored = c.encrypt("EAAB-secret-page-token")
            stored shouldStartWith "v1:"
            stored.contains("secret") shouldBe false
            c.decrypt(stored) shouldBe "EAAB-secret-page-token"
            c.encrypt("same") shouldNotBe c.encrypt("same")
        }

        @Test
        fun `another key cannot read it`() {
            val stored = SocialCrypto(TestProps.props(tokenKey = key)).encrypt("x")
            val other = SocialCrypto(TestProps.props(tokenKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })))
            shouldThrow<Exception> { other.decrypt(stored) }
        }

        @Test
        fun `no key, no storing`() {
            val c = SocialCrypto(TestProps.props(tokenKey = ""))
            c.configured shouldBe false
            shouldThrow<SocialConfigException> { c.encrypt("x") }
        }

        @Test
        fun `PKCE S256 matches RFC 7636 appendix B`() {
            SocialCrypto.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        }

        @Test
        fun `random tokens are long and alphanumeric`() {
            val t = SocialCrypto.randomToken(43)
            t.length shouldBe 43
            Regex("^[A-Za-z0-9]+$").matches(t) shouldBe true
        }
    }

    @Nested
    inner class Rules {
        private val img = MediaInfo(SocialMediaKind.IMAGE)
        private val vid = MediaInfo(SocialMediaKind.VIDEO)

        @Test
        fun `X counts a link as 23 and an emoji as 2`() {
            SocialRules.count("https://www.boat4you.com/en/boat/gulet-allure-11769", SocialRules.Counting.X_WEIGHTED) shouldBe 23
            SocialRules.count("čćžšđ", SocialRules.Counting.X_WEIGHTED) shouldBe 5
            SocialRules.count("\uD83D\uDE00", SocialRules.Counting.X_WEIGHTED) shouldBe 2
            SocialRules.errors(SocialPlatform.X, "a".repeat(280), null, emptyList()).shouldBeEmpty()
            SocialRules.errors(SocialPlatform.X, "a".repeat(281), null, emptyList()) shouldContain "text too long (281/280)"
        }

        @Test
        fun `Instagram needs media, at most 30 hashtags, photos 4 to 5 up to 1_91 to 1`() {
            SocialRules.errors(SocialPlatform.INSTAGRAM, "hi", null, emptyList()) shouldContain "needs a photo or video"
            SocialRules.errors(SocialPlatform.INSTAGRAM, (0..30).joinToString(" ") { "#t$it" }, null, listOf(img)) shouldContain "at most 30 hashtags (31)"
            SocialRules.errors(SocialPlatform.INSTAGRAM, "x", null, listOf(MediaInfo(SocialMediaKind.IMAGE, 1080, 1350))).shouldBeEmpty()
            SocialRules.errors(SocialPlatform.INSTAGRAM, "x", null, listOf(MediaInfo(SocialMediaKind.IMAGE, 1080, 1920))) shouldContain
                "Instagram photos must be between 4:5 and 1.91:1"
        }

        @Test
        fun `YouTube needs one video and a title without angle brackets`() {
            SocialRules.errors(SocialPlatform.YOUTUBE, "d", null, emptyList()) shouldBe listOf("needs a video", "title required")
            SocialRules.errors(SocialPlatform.YOUTUBE, "d", "Allure", listOf(vid)).shouldBeEmpty()
            SocialRules.errors(SocialPlatform.YOUTUBE, "a <b>", "Allure", listOf(vid)) shouldContain "YouTube refuses < and >"
        }

        @Test
        fun `TikTok, X and LinkedIn do not mix photos and a video`() {
            listOf(SocialPlatform.TIKTOK, SocialPlatform.X, SocialPlatform.LINKEDIN, SocialPlatform.FACEBOOK).forEach {
                SocialRules.errors(it, "x", null, listOf(img, vid)) shouldContain "photos and a video cannot go together"
            }
        }

        @Test
        fun `Bluesky counts graphemes`() {
            SocialRules.count("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", SocialRules.Counting.GRAPHEMES) shouldBe 1
        }
    }

    @Nested
    inner class PostStatus {
        private fun derive(
            vararg t: SocialTargetStatus,
            current: SocialPostStatus = SocialPostStatus.PUBLISHING,
        ) = SocialPostService.deriveStatus(t.toList(), current)

        @Test
        fun `the post follows its channels`() {
            derive(SocialTargetStatus.PUBLISHED, SocialTargetStatus.PUBLISHED) shouldBe SocialPostStatus.PUBLISHED
            derive(SocialTargetStatus.PUBLISHED, SocialTargetStatus.FAILED) shouldBe SocialPostStatus.PARTIAL
            derive(SocialTargetStatus.FAILED, SocialTargetStatus.CANCELLED) shouldBe SocialPostStatus.FAILED
            derive(SocialTargetStatus.CANCELLED, SocialTargetStatus.CANCELLED) shouldBe SocialPostStatus.CANCELLED
            derive(SocialTargetStatus.PUBLISHED, SocialTargetStatus.PUBLISHING) shouldBe SocialPostStatus.PUBLISHING
            derive(SocialTargetStatus.PENDING, SocialTargetStatus.PENDING) shouldBe SocialPostStatus.DRAFT
        }

        @Test
        fun `a scheduled post stays scheduled until something happened, a retry shows as publishing`() {
            derive(SocialTargetStatus.SCHEDULED, current = SocialPostStatus.SCHEDULED) shouldBe SocialPostStatus.SCHEDULED
            derive(SocialTargetStatus.SCHEDULED, SocialTargetStatus.PUBLISHED, current = SocialPostStatus.SCHEDULED) shouldBe SocialPostStatus.PUBLISHING
        }

        @Test
        fun `links must be http(s) without credentials`() {
            SocialPostService.isHttpUrl("https://www.boat4you.com/en") shouldBe true
            SocialPostService.isHttpUrl("javascript:alert(1)") shouldBe false
            SocialPostService.isHttpUrl("https://u:p@boat4you.com") shouldBe false
        }
    }

    @Nested
    inner class Media {
        private fun bytes(vararg b: Int) = ByteArray(16).also { b.forEachIndexed { i, v -> it[i] = v.toByte() } }

        @Test
        fun `type comes from the magic bytes`() {
            SocialMediaStore.sniff(bytes(0xFF, 0xD8, 0xFF))?.mime shouldBe "image/jpeg"
            SocialMediaStore.sniff(bytes(0x89, 'P'.code, 'N'.code, 'G'.code))?.mime shouldBe "image/png"
            SocialMediaStore.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))?.mime shouldBe "image/webp"
            SocialMediaStore.sniff("\u0000\u0000\u0000\u0018ftypmp42\u0000\u0000".toByteArray(Charsets.ISO_8859_1))?.mime shouldBe "video/mp4"
            SocialMediaStore.sniff("\u0000\u0000\u0000\u0014ftypqt  \u0000\u0000".toByteArray(Charsets.ISO_8859_1))?.mime shouldBe "video/quicktime"
            SocialMediaStore.sniff("<?php echo 1; ?>".toByteArray()) shouldBe null
            SocialMediaStore.sniff("GIF89a..........".toByteArray()) shouldBe null
        }

        @Test
        fun `stored names lose path parts and odd characters`() {
            val jpg = SocialMediaStore.sniff(bytes(0xFF, 0xD8, 0xFF))!!
            SocialMediaStore.safeName("../../etc/passwd", jpg) shouldBe "passwd"
            SocialMediaStore.safeName("C:\\x\\Kornati 2027 (1).jpg", jpg) shouldBe "Kornati 2027 _1_.jpg"
            SocialMediaStore.safeName(null, jpg) shouldBe "media.jpg"
        }
    }

    @Nested
    inner class Misc {
        @Test
        fun `Bluesky link facets use UTF-8 byte offsets`() {
            val text = "Kornati č https://www.boat4you.com/hr."
            val facet = BlueskyConnector.linkFacets(text).single()

            @Suppress("UNCHECKED_CAST")
            val index = facet["index"] as Map<String, Int>
            val bytes = text.toByteArray(Charsets.UTF_8)
            String(bytes, index.getValue("byteStart"), index.getValue("byteEnd") - index.getValue("byteStart"), Charsets.UTF_8) shouldBe
                "https://www.boat4you.com/hr"
        }

        @Test
        fun `Higgsfield answers in the common shapes`() {
            val m = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            HiggsfieldClient.parse(m.readTree("""{"status":"in_progress"}""")).done shouldBe false
            HiggsfieldClient.parse(m.readTree("""{"status":"completed","video":{"url":"https://cdn.x/v.mp4"}}""")).videoUrl shouldBe "https://cdn.x/v.mp4"
            HiggsfieldClient.parse(m.readTree("""{"status":"succeeded","output":{"video":{"url":"https://cdn.x/w.mp4"}}}""")).videoUrl shouldBe
                "https://cdn.x/w.mp4"
            HiggsfieldClient.parse(m.readTree("""{"status":"failed","error":"nsfw"}""")).failed shouldBe true
            HiggsfieldClient.parse(m.readTree("""{"status":"completed"}""")).failed shouldBe true
        }

        @Test
        fun `a page name points to its site`() {
            SiteGuess.fromText("Catamaran Charter Greece") shouldBe SiteIds.CATAMARAN_GREECE
            SiteGuess.fromText("Catamaran Croatia Charter") shouldBe SiteIds.CATAMARAN_CROATIA
            SiteGuess.fromText("Croatia Yachting") shouldBe SiteIds.CROATIA_YACHTING
            SiteGuess.fromText("Europe Yachts") shouldBe SiteIds.EUROPE_YACHTS
            SiteGuess.fromText("boat4you @boat4you") shouldBe SiteIds.BOAT4YOU
            SiteGuess.fromText("Some other page") shouldBe null
        }

        @Test
        fun `OAuth callback paths and redirect parameters`() {
            SocialAccountService.callbackPathOf(SocialPlatform.INSTAGRAM) shouldBe "meta"
            SocialAccountService.callbackPathOf(SocialPlatform.GOOGLE_BUSINESS) shouldBe "google-business"
            SocialAccountService.withParams("https://admin.boat4you.com/social/channels", mapOf("error" to "a b&c")) shouldBe
                "https://admin.boat4you.com/social/channels?error=a%20b%26c"
        }

        @Test
        fun `the AI's charter week is a Saturday in the season`() {
            val r = Random(42)
            repeat(200) {
                val today = LocalDate.of(2026, 1, 1).plusDays(it * 3L)
                val week = SocialBoatPicker.charterWeek(today, r)
                week.dayOfWeek shouldBe DayOfWeek.SATURDAY
                (week.monthValue in 5..10) shouldBe true
                week.isAfter(today.plusWeeks(2)) shouldBe true
            }
        }
    }
}
