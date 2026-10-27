package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import hr.workspace.boat4you.domains.contactcenter.service.UserAgentParser.ParsedUa
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Device class, browser (with major version) and OS from a User-Agent string, for the visit row. */
class UserAgentParserTest {
    @Test
    fun `device, browser and os from real user agents`() {
        UserAgentParser.parse(CHROME_UA) shouldBe ParsedUa("DESKTOP", "Chrome 131", "Windows")
        UserAgentParser.parse(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1",
        ) shouldBe ParsedUa("MOBILE", "Safari 18", "iOS")
        UserAgentParser.parse(
            "Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1",
        ) shouldBe ParsedUa("TABLET", "Safari 17", "iOS")
        UserAgentParser.parse(
            "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        ) shouldBe ParsedUa("TABLET", "Chrome 130", "Android")
        UserAgentParser.parse(
            "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36",
        ) shouldBe ParsedUa("MOBILE", "Samsung Internet 26", "Android")
        UserAgentParser.parse("$CHROME_UA Edg/131.0.0.0").browser shouldBe "Edge 131"
        UserAgentParser.parse(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.7; rv:132.0) Gecko/20100101 Firefox/132.0",
        ) shouldBe ParsedUa("DESKTOP", "Firefox 132", "macOS")
        UserAgentParser.parse(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1",
        ) shouldBe ParsedUa("MOBILE", "Chrome 131", "iOS")
        UserAgentParser.parse(null) shouldBe ParsedUa("DESKTOP", null, null)
    }

    @Test
    fun `blank and unrecognised agents get no guess, and a long version number still fits the browser column`() {
        UserAgentParser.parse("  ") shouldBe ParsedUa("DESKTOP", null, null)
        UserAgentParser.parse("SomeTool/1.0") shouldBe ParsedUa("DESKTOP", "Other", "Other")
        UserAgentParser.parse("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) Chrome/${"9".repeat(300)}").browser!!.length shouldBe 40 // VARCHAR(40)
    }
}
