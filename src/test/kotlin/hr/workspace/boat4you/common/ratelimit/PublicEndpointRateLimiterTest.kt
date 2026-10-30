package hr.workspace.boat4you.common.ratelimit

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

class PublicEndpointRateLimiterTest {
    private val limiter =
        PublicEndpointRateLimiter(
            reservationCapacity = 5,
            reservationWindowSeconds = 60,
            inquiryCapacity = 5,
            inquiryWindowSeconds = 60,
            setPasswordCapacity = 5,
            setPasswordWindowSeconds = 60,
            loginCapacity = 10,
            loginWindowSeconds = 60,
            registerCapacity = 3,
            registerWindowSeconds = 60,
            passwordResetCapacity = 3,
            passwordResetWindowSeconds = 60,
            verifyEmailCapacity = 5,
            verifyEmailWindowSeconds = 60,
            reviewCapacity = 10,
            reviewWindowSeconds = 60,
            chatSessionCapacity = 5,
            chatSessionWindowSeconds = 600,
            chatMessageCapacity = 40,
            chatMessageWindowSeconds = 600,
            chatNameCapacity = 10,
            chatNameWindowSeconds = 600,
            ccVisitCapacity = 60,
            ccVisitWindowSeconds = 600,
            ccBeatCapacity = 600,
            ccBeatWindowSeconds = 600,
            ccHandoffCapacity = 10,
            ccHandoffWindowSeconds = 600,
        )

    private fun hit(
        uri: String,
        xri: String? = "203.0.113.7",
        xff: String = "1.1.1.1",
        method: String = "POST",
    ): MockHttpServletResponse {
        val req =
            MockHttpServletRequest(method, uri).apply {
                remoteAddr = "127.0.0.1"
                xri?.let { addHeader("X-Real-IP", it) }
                addHeader("X-Forwarded-For", xff)
            }
        return MockHttpServletResponse().also { limiter.doFilter(req, it, MockFilterChain()) }
    }

    @Test
    fun `tracker budgets per IP`() {
        limiter.clock = CcTestClock() // frozen: cc-beat refills 1 token/s, a real clock would make the 601st-hit 429 depend on speed
        repeat(60) { hit("/public/cc/v1/visit").status shouldBe 200 }
        hit("/public/cc/v1/visit").let {
            it.status shouldBe 429
            it.getHeader("Retry-After") shouldBe "600"
        }
        hit("/public/cc/v1/%76isit").status shouldBe 429 // F1-022: the encoded spelling reaches the same controller, so it spends the same budget
        repeat(600) { hit("/public/cc/v1/beat").status shouldBe 200 }
        hit("/public/cc/v1/beat").status shouldBe 429
        repeat(10) { hit("/public/cc/v1/handoff").status shouldBe 200 }
        hit("/public/cc/v1/handoff").status shouldBe 429
    }

    @Test
    fun `without a usable X-Real-IP the realIp rules key on the socket address, a forged X-Forwarded-For buys nothing`() {
        limiter.clock = CcTestClock() // frozen, so the rule does not refill while the loop runs
        repeat(5) { hit("/public/chat/sessions", xri = null, xff = "10.0.0.$it").status shouldBe 200 }
        hit("/public/chat/sessions", xri = null, xff = "10.9.9.9").status shouldBe 429
    }

    @Test
    fun `tracker rules key on X-Real-IP, inquiries keep the left-most X-Forwarded-For`() {
        repeat(60) { hit("/public/cc/v1/visit", xff = "10.0.0.$it").status shouldBe 200 }
        hit("/public/cc/v1/visit", xff = "10.0.0.99").status shouldBe 429 // a forged XFF does not buy budget
        hit("/public/cc/v1/visit", xri = "203.0.113.8").status shouldBe 200
        repeat(5) { hit("/public/inquiries", xri = "91.98.209.180", xff = "1.1.1.1").status shouldBe 200 }
        hit("/public/inquiries", xri = "91.98.209.180", xff = "1.1.1.1").status shouldBe 429
        hit("/public/inquiries", xri = "91.98.209.180", xff = "2.2.2.2").status shouldBe 200 // boat4you-web server action forwards the visitor IP
    }

    @Test
    fun `chat limits are unchanged and presence stays unlimited`() {
        repeat(5) { hit("/public/chat/sessions").status shouldBe 200 }
        hit("/public/chat/sessions").status shouldBe 429
        repeat(100) { hit("/public/chat/sessions/t0k/presence").status shouldBe 200 }
        repeat(40) { hit("/public/chat/sessions/t0k/messages").status shouldBe 200 }
        hit("/public/chat/sessions/t0k/messages").status shouldBe 429
    }

    @Test
    fun `chat rules key on X-Real-IP too`() { // BACKEND-22: the browser calls chat directly, a forged XFF buys nothing
        repeat(5) { hit("/public/chat/sessions", xff = "10.0.0.$it").status shouldBe 200 }
        hit("/public/chat/sessions", xff = "10.0.0.99").status shouldBe 429
        hit("/public/chat/sessions", xri = "203.0.113.8").status shouldBe 200
        repeat(10) { hit("/public/reviews/request/tok", xri = "91.98.209.180", xff = "1.1.1.1").status shouldBe 200 } // server action: XFF
        hit("/public/reviews/request/tok", xri = "91.98.209.180", xff = "2.2.2.2").status shouldBe 200
    }

    @Test
    fun `idle buckets are pruned without changing any decision`() {
        val clock = CcTestClock().also { limiter.clock = it }
        repeat(5) { hit("/public/chat/sessions").status shouldBe 200 }
        hit("/public/chat/sessions").status shouldBe 429
        limiter.pruneIdleBuckets() shouldBe 0 // inside its window: kept
        clock.advance(Duration.ofSeconds(600))
        limiter.pruneIdleBuckets() shouldBe 1 // a full window idle = a full bucket again
        repeat(5) { hit("/public/chat/sessions").status shouldBe 200 } // the same as a refilled bucket
        hit("/public/chat/sessions").status shouldBe 429
    }

    @Test
    fun `a forged X-Forwarded-For buys no budget on any of the six X-Real-IP rules`() {
        limiter.clock = CcTestClock() // frozen, so no rule refills while the loop runs
        listOf(
            "/public/cc/v1/visit" to 60,
            "/public/cc/v1/beat" to 600,
            "/public/cc/v1/handoff" to 10,
            "/public/chat/sessions" to 5,
            "/public/chat/sessions/t0k/messages" to 40,
            "/public/chat/sessions/t0k/name" to 10,
        ).forEach { (uri, budget) ->
            repeat(budget) { hit(uri, xff = "10.1.0.$it").status shouldBe 200 }
            hit(uri, xff = "10.9.9.9").status shouldBe 429
            hit(uri, xri = "203.0.113.8", xff = "10.9.9.9").status shouldBe 200
        }
    }

    @Test
    fun `tokens refill by the injected clock, counted from the last hit`() {
        val clock = CcTestClock().also { limiter.clock = it }
        repeat(5) { hit("/public/chat/sessions").status shouldBe 200 } // 5 per 600 s = one token per 120 s
        hit("/public/chat/sessions").status shouldBe 429
        clock.advance(Duration.ofSeconds(120))
        hit("/public/chat/sessions").status shouldBe 200
        hit("/public/chat/sessions").status shouldBe 429
        clock.advance(Duration.ofSeconds(120))
        hit("/public/chat/sessions").status shouldBe 200 // 120 s since the last hit = one token, not 240 s = two
        hit("/public/chat/sessions").status shouldBe 429
    }

    @Test
    fun `prune measures each bucket against its own rule window and splits an IPv6 key`() {
        val clock = CcTestClock().also { limiter.clock = it }
        hit("/public/reservations").status shouldBe 200 // window 60 s
        hit("/public/chat/sessions", xri = "2001:db8::1").status shouldBe 200 // window 600 s, key "chat-session:2001:db8::1"
        clock.advance(Duration.ofSeconds(60))
        limiter.pruneIdleBuckets() shouldBe 1 // only the reservations bucket has been idle for its window
        clock.advance(Duration.ofSeconds(540))
        limiter.pruneIdleBuckets() shouldBe 1 // now the chat bucket has too
        limiter.pruneIdleBuckets() shouldBe 0
    }

    @Test
    fun `pruneIdleBuckets is scheduled once a minute`() {
        val scheduled = PublicEndpointRateLimiter::class.java.getMethod("pruneIdleBuckets").getAnnotation(Scheduled::class.java)
        scheduled?.fixedDelay shouldBe 60_000L
    }
}
