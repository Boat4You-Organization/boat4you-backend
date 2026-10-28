package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The visit-time bot flags: a known crawler or automation token, the generic bot pattern, webdriver, and a 0-px viewport. */
class BotFilterTest {
    @Test
    fun `known bots are flagged and real browsers are not`() {
        BotFilter.atVisit(
            "meta-externalagent/1.1 (+https://developers.facebook.com/docs/sharing/webmasters/crawler)",
            false,
            1280,
            800,
        ) shouldBe "ua:meta-externalagent"
        BotFilter.atVisit(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/120.0.0.0 Safari/537.36",
            false,
            800,
            600,
        ) shouldBe "ua:headlesschrome"
        BotFilter.atVisit("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", false, null, null) shouldBe "ua:googlebot"
        BotFilter.atVisit(
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; GPTBot/1.2; +https://openai.com/gptbot)",
            false,
            null,
            null,
        ) shouldBe "ua:gptbot"
        BotFilter.atVisit(
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; ClaudeBot/1.0; +claudebot@anthropic.com)",
            false,
            null,
            null,
        ) shouldBe "ua:claudebot"
        BotFilter.atVisit("curl/8.7.1", false, null, null) shouldBe "ua:curl/"
        BotFilter.atVisit("Mozilla/5.0 (compatible; SomeNewCrawler/1.0)", false, null, null) shouldBe "ua:pattern"
        BotFilter.atVisit("", false, 1280, 800) shouldBe "empty_ua"
        BotFilter.atVisit(CHROME_UA, true, 1280, 800) shouldBe "webdriver"
        BotFilter.atVisit(CHROME_UA, false, 0, 0) shouldBe "zero_viewport"
        listOf(
            CHROME_UA,
            "Mozilla/5.0 (Linux; Android 10; CUBOT X30) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/490.0]",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.7; rv:132.0) Gecko/20100101 Firefox/132.0",
        ).forEach { BotFilter.atVisit(it, false, 390, 844) shouldBe null }
    }
}
