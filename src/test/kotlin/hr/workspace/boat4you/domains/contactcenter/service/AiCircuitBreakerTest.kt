package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEvent
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant

class AiCircuitBreakerTest {
    private var now = Instant.parse("2027-07-01T10:00:00Z")
    private val saved = mutableListOf<CcSecurityEvent>()
    private var mails = 0

    private fun newBreaker(restore: List<CcSecurityEvent> = emptyList()): AiCircuitBreaker {
        val settings =
            object : CcAiSettings {
                override fun current() = CcAiSettings.Values()

                override fun update(
                    key: String,
                    value: String?,
                ) = false
            }
        val events =
            mock(CcSecurityEventRepository::class.java) { inv ->
                when (inv.method.name) {
                    "save" -> (inv.arguments[0] as CcSecurityEvent).also { saved += it }
                    "latestBreakerEventPerScope" -> restore
                    else -> null
                }
            }
        val email =
            mock(EmailService::class.java) { inv ->
                if (inv.method.name == "sendEmail") mails++
                null
            }
        return AiCircuitBreaker(settings, events, email, "info@example.com").also {
            it.mailExecutor = { r -> r.run() }
            it.clock = { now }
        }
    }

    private val b = newBreaker()

    @Test
    fun `51 visitor messages in a minute across sites open the global breaker once`() {
        repeat(26) { b.recordVisitorMessage("boat4you") }
        repeat(24) { b.recordVisitorMessage("catamaran-greece") }
        b.isOpen("europe-yachts") shouldBe false
        b.recordVisitorMessage("catamaran-greece")
        b.isOpen("europe-yachts") shouldBe true
        saved.map { Triple(it.type, it.siteId, it.detail) } shouldBe listOf(Triple("BREAKER_OPEN", null, "auto: MESSAGES 51/min > 50"))
        mails shouldBe 1
    }

    @Test
    fun `one site above its own threshold closes only that site`() {
        repeat(31) { b.recordVisitorMessage("catamaran-greece") }
        b.isOpen("catamaran-greece") shouldBe true
        b.isOpen("boat4you") shouldBe false
        saved.single().siteId shouldBe "catamaran-greece"
    }

    @Test
    fun `an automatic open closes after the cooldown only when the rate is low`() {
        repeat(6) { b.recordLock(if (it % 2 == 0) "boat4you" else "europe-yachts") }
        b.isOpen("boat4you") shouldBe true
        now = now.plusSeconds(299)
        b.tick()
        b.isOpen("boat4you") shouldBe true
        repeat(51) { b.recordVisitorMessage(if (it % 2 == 0) "boat4you" else "europe-yachts") }
        now = now.plusSeconds(1)
        b.tick()
        b.isOpen("boat4you") shouldBe true // still a surge: wait another 5 min
        now = now.plusSeconds(300)
        b.tick()
        b.isOpen("boat4you") shouldBe false
        saved.map { it.type } shouldBe listOf("BREAKER_OPEN", "BREAKER_CLOSE")
        saved.last().detail shouldBe "auto: below thresholds after 5 min"
    }

    @Test
    fun `a manual open never closes by itself and survives a restart`() {
        b.open(null, "mario@boat4you.com")
        now = now.plusSeconds(3600)
        b.tick()
        b.isOpen("boat4you") shouldBe true
        saved.last().detail shouldBe "manual by mario@boat4you.com"
        val restarted = newBreaker(restore = listOf(saved.last()))
        restarted.restoreManualState()
        restarted.isOpen("croatia-yachting") shouldBe true
        val autoOpen =
            CcSecurityEvent().apply {
                type = "BREAKER_OPEN"
                detail = "auto: MESSAGES 51/min > 50"
            }
        val afterAuto = newBreaker(restore = listOf(autoOpen))
        afterAuto.restoreManualState()
        afterAuto.isOpen("boat4you") shouldBe false
        b.close(null, "mario@boat4you.com")
        b.isOpen("boat4you") shouldBe false
        mails shouldBe 0
    }

    @Test
    fun `one notice per session per open period, at most 5 mails an hour`() {
        b.open("catamaran-greece", "m")
        b.claimNotice(7) shouldBe true
        b.claimNotice(7) shouldBe false
        b.close("catamaran-greece", "m")
        b.claimNotice(7) shouldBe true
        repeat(6) {
            repeat(31) { b.recordVisitorMessage("catamaran-greece") }
            now = now.plusSeconds(301)
            b.tick()
            now = now.plusSeconds(61)
        }
        mails shouldBe 5
    }

    @Test
    fun `notices stay bounded`() {
        repeat(AiCircuitBreaker.MAX_NOTICES + 5) { b.claimNotice(it.toLong()) }
        b.claimNotice(AiCircuitBreaker.MAX_NOTICES + 4L) shouldBe false // the newest session is still remembered
        b.claimNotice(0) shouldBe true // the oldest was evicted, so it may get a second notice
    }
}
