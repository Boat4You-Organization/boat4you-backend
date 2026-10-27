package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration

class HandoffTokenStoreTest {
    private val clock = CcTestClock()
    private val store = HandoffTokenStore().also { it.clock = clock }
    private val key = "a0000000-0000-4000-8000-000000000001"

    @Test
    fun `a token is single-use and burned by the wrong site`() {
        val t = store.issue(key, "catamaran-greece", "boat4you")!!
        HandoffTokenStore.TOKEN_FORMAT.matches(t) shouldBe true
        store.consume(t, "boat4you")!!.visitorKey shouldBe key
        store.consume(t, "boat4you") shouldBe null
        val t2 = store.issue(key, "catamaran-greece", "boat4you")!!
        store.consume(t2, "europe-yachts") shouldBe null
        store.consume(t2, "boat4you") shouldBe null
        store.consume("not a token", "boat4you") shouldBe null
    }

    @Test
    fun `tokens expire after 120 s and the store is bounded`() {
        val t = store.issue(key, "catamaran-greece", "boat4you")!!
        clock.advance(Duration.ofSeconds(121))
        store.consume(t, "boat4you") shouldBe null
        repeat(HandoffTokenStore.MAX_TOKENS) { store.issue(key, "catamaran-greece", "boat4you") }
        store.issue(key, "catamaran-greece", "boat4you") shouldBe null
        clock.advance(Duration.ofSeconds(121))
        store.issue(key, "catamaran-greece", "boat4you") shouldNotBe null
        store.size() shouldBe 1
    }
}
