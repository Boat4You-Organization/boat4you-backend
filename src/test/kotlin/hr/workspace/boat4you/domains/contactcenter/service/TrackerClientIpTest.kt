package hr.workspace.boat4you.domains.contactcenter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/** The client address of a tracker request: nginx's `X-Real-IP` only, and only when it is an address, never a name. */
class TrackerClientIpTest {
    private fun req(xri: String?) =
        MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7")
            xri?.let { addHeader("X-Real-IP", it) }
        }

    @Test
    fun `only an IP literal in X-Real-IP is trusted`() { // BACKEND-22/24, no DNS lookup on a hostname
        TrackerClientIp.of(req("203.0.113.7")) shouldBe "203.0.113.7"
        TrackerClientIp.of(req("2001:db8::1")) shouldBe "2001:db8::1"
        TrackerClientIp.of(req("evil.example.com")) shouldBe "127.0.0.1"
        TrackerClientIp.of(req(null)) shouldBe "127.0.0.1"
    }
}
