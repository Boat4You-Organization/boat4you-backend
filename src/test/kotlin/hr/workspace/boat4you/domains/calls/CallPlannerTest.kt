package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Planning from inquiries and Mario's admin actions (design §2.2, §2.3, §2.5). 3.11.2026 13:00 UTC = Tuesday 14:00 Zagreb. */
class CallPlannerTest : CallsJpaTest() {
    private val now: Instant = Instant.parse("2026-11-03T13:00:00Z")
    private val planner by lazy { CallPlanner(calls, inquiries, settings, jdbc, Clock.fixed(now, ZoneOffset.UTC)) }

    private fun callsOf(inquiryId: Long) = calls.findByInquiryIdOrderByCreatedAtDesc(inquiryId)

    @Test
    fun `a fresh inquiry with consent gets one confirmation call 5 minutes later inside the window`() {
        val i = inquiry { locale = "it" }
        planner.onInquiryCreated(i)
        val c = callsOf(i.id!!).single()
        c.kind shouldBe CallKind.CONFIRMATION
        c.status shouldBe CallStatus.SCHEDULED
        c.attempt shouldBe 1
        c.scheduledAt shouldBe Instant.parse("2026-11-03T13:05:00Z")
        c.toNumber shouldBe "+385912345678"
        c.language shouldBe "it"
    }

    @Test
    fun `an inquiry without consent or with an unreadable phone gets no call`() {
        val a = inquiry(consent = false)
        val b = inquiry(phone = null)
        val c = inquiry { doNotCall = true }
        listOf(a, b, c).forEach { planner.onInquiryCreated(it) }
        calls.count() shouldBe 0
    }

    @Test
    fun `offer sent cancels the pending confirmation and schedules one follow-up 48 h later`() {
        val i = inquiry()
        planner.onInquiryCreated(i)
        planner.onOfferSent(i.id!!, "  Lagoon 46 i Bali 4.6, 12.–19.6., 11.900 €  ")
        planner.onOfferSent(i.id!!, "Lagoon 46, 12.–19.6., 11.900 €") // a second click moves the same follow-up
        val all = callsOf(i.id!!)
        all.single { it.kind == CallKind.CONFIRMATION }.status shouldBe CallStatus.CANCELLED
        val f = all.filter { it.kind == CallKind.FOLLOW_UP }
        f shouldHaveSize 1
        f.single().scheduledAt shouldBe Instant.parse("2026-11-05T13:00:00Z")
        val saved = inquiries.findById(i.id!!).get()
        saved.offerNote shouldBe "Lagoon 46, 12.–19.6., 11.900 €"
        saved.offerSentAt shouldBe now
    }

    @Test
    fun `do not call on one inquiry cancels every scheduled call for the same phone`() {
        val a = inquiry()
        val b = inquiry()
        val other = inquiry(phone = "+385981111111")
        listOf(a, b, other).forEach { planner.onInquiryCreated(it) }
        planner.markDoNotCall("+385912345678")
        (callsOf(a.id!!) + callsOf(b.id!!)).map { it.status }.toSet() shouldBe setOf(CallStatus.CANCELLED)
        callsOf(other.id!!).single().status shouldBe CallStatus.SCHEDULED
        inquiries.findById(b.id!!).get().doNotCall shouldBe true
        inquiries.findById(other.id!!).get().doNotCall shouldBe false
    }

    @Test
    fun `human takeover cancels scheduled AI calls but keeps Mario's callback task`() {
        val i = inquiry()
        planner.onInquiryCreated(i)
        planner.createHumanTask(i, callsOf(i.id!!).single(), "ready to book")
        planner.onHumanTakeover(i.id!!)
        val all = callsOf(i.id!!)
        all.single { it.kind == CallKind.CONFIRMATION }.status shouldBe CallStatus.CANCELLED
        all.single { it.kind == CallKind.HUMAN_CALLBACK }.status shouldBe CallStatus.SCHEDULED
        inquiries.findById(i.id!!).get().humanTakeoverAt shouldBe now
    }

    @Test
    fun `a manual call goes now inside the window and at the next slot outside it unless confirmed`() {
        val i = inquiry()
        planner.scheduleManual(i.id!!, now, false).scheduledAt shouldBe now
        val night = Instant.parse("2026-11-03T22:00:00Z")
        planner.scheduleManual(i.id!!, night, false).scheduledAt shouldBe Instant.parse("2026-11-04T08:30:00Z")
        planner.scheduleManual(i.id!!, night, true).scheduledAt shouldBe night
    }

    @Test
    fun `a busy call is retried once as a new attempt 30 minutes later, the third no-answer stops`() {
        val i = inquiry()
        val first = call(i, status = CallStatus.BUSY)
        planner.scheduleRetry(first, CallStatus.BUSY, now)
        val second = callsOf(i.id!!).first { it.status == CallStatus.SCHEDULED }
        second.attempt shouldBe 2
        second.scheduledAt shouldBe Instant.parse("2026-11-03T13:30:00Z")
        val third = call(i, status = CallStatus.NO_ANSWER, attempt = 3)
        planner.scheduleRetry(third, CallStatus.NO_ANSWER, now)
        callsOf(i.id!!).filter { it.attempt == 4 }.shouldBeEmpty()
    }
}
