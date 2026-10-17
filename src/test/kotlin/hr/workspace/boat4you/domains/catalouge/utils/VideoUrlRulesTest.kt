package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The film URLs PATCH /admin/yachts/{id}/media accepts (V9_92): YouTube and Vimeo forms the sites embed, nothing else. */
class VideoUrlRulesTest {
    @Test
    fun `YouTube and Vimeo forms are accepted`() {
        listOf(
            "https://vimeo.com/982130882", // gulet Allure 11769, owner request 17.10.2026
            "https://www.vimeo.com/982130882",
            "https://vimeo.com/982130882/1a2b3c4d5e", // unlisted film
            "https://player.vimeo.com/video/982130882",
            "https://player.vimeo.com/video/982130882?h=1a2b3c&autoplay=1",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=30s",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
        ).forEach { (it to VideoUrlRules.isAccepted(it)) shouldBe (it to true) }
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "",
            "vimeo.com/982130882", // no scheme
            "javascript:alert(1)//vimeo.com/1",
            "ftp://vimeo.com/982130882",
            "https://evil.example/vimeo.com/982130882",
            "https://vimeo.com.evil.example/982130882",
            "https://user@vimeo.com/982130882",
            "https://vimeo.com:8443/982130882",
            "https://vimeo.com/channels/staffpicks",
            "https://player.vimeo.com/982130882",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?list=PL123",
            "https://www.youtube.com/@boat4you",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ <script>",
            "https://vimeo.com/" + "1".repeat(VideoUrlRules.MAX_LENGTH),
        ).forEach { (it to VideoUrlRules.isAccepted(it)) shouldBe (it to false) }
    }
}
