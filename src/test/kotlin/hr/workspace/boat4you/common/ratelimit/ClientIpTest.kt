package hr.workspace.boat4you.common.ratelimit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/** Whose address a request counts as: the connecting one, or the visitor our own web server names (F1-022). */
class ClientIpTest {
    private fun ip(
        xri: String?,
        xff: String?,
        remote: String = "127.0.0.1",
    ) = ClientIp.of(
        MockHttpServletRequest().apply {
            remoteAddr = remote
            xri?.let { addHeader("X-Real-IP", it) }
            xff?.let { addHeader("X-Forwarded-For", it) }
        },
    )

    @Test
    fun `a direct caller is the address nginx saw, whatever the header claims`() {
        ip("203.0.113.7", "1.1.1.1, 203.0.113.7") shouldBe "203.0.113.7"
        ip("203.0.113.7", "91.98.209.180") shouldBe "203.0.113.7"
        ip("203.0.113.7", null) shouldBe "203.0.113.7"
        ip("2001:db8::1", "1.1.1.1") shouldBe "2001:db8::1"
    }

    @Test
    fun `our web servers name the visitor`() {
        ip("91.98.209.180", "198.51.100.4, 91.98.209.180") shouldBe "198.51.100.4"
        ip("91.98.209.185", "2001:db8::9, 91.98.209.185") shouldBe "2001:db8::9"
        ip("192.168.55.5", "198.51.100.4") shouldBe "198.51.100.4"
    }

    @Test
    fun `a web server call without a usable visitor address is the server itself`() {
        ip("91.98.209.180", "91.98.209.180") shouldBe "91.98.209.180" // the server sent none, nginx appended its own
        ip("91.98.209.180", null) shouldBe "91.98.209.180"
        ip("91.98.209.180", "evil.example, 1.1.1.1") shouldBe "91.98.209.180"
        ip("91.98.209.180", " , 1.1.1.1") shouldBe "91.98.209.180"
    }

    @Test
    fun `without X-Real-IP the socket address decides, and a forged X-Real-IP name is ignored`() {
        ip(null, "1.1.1.1") shouldBe "127.0.0.1"
        ip("evil.example", "1.1.1.1", remote = "203.0.113.9") shouldBe "203.0.113.9"
        ip(null, "198.51.100.4", remote = "192.168.55.1") shouldBe "198.51.100.4"
    }
}
