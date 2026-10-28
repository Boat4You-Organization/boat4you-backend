package hr.workspace.boat4you.domains.contactcenter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChatSiteResolverTest {
    @Test
    fun `the browser Origin decides, a server call may name a known site, everything else is boat4you`() {
        ChatSiteResolver.resolve("https://www.catamaran-charter-greece.com", null) shouldBe "catamaran-greece"
        ChatSiteResolver.resolve("https://www.catamaran-charter-greece.com", "europe-yachts") shouldBe "catamaran-greece"
        ChatSiteResolver.resolve("https://www.boat4you.com", null) shouldBe "boat4you"
        ChatSiteResolver.resolve(null, "europe-yachts") shouldBe "europe-yachts"
        ChatSiteResolver.resolve(null, "evil-site") shouldBe "boat4you"
        ChatSiteResolver.resolve("https://evil.example", "europe-yachts") shouldBe "boat4you"
        ChatSiteResolver.resolve("not a uri", null) shouldBe "boat4you"
        ChatSiteResolver.resolve(null, null) shouldBe "boat4you"
    }
}
