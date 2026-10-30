package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

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
        listOf(a, other).forEach { planner.onInquiryCreated(it) }
        call(b, kind = CallKind.FOLLOW_UP) // b's own confirmation would be SKIPPED as SAME_PERSON; its follow-up is scheduled
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
    fun `a manual call goes now inside the window and otherwise at the client's next slot, never outside it`() {
        val i = inquiry()
        planner.scheduleManual(i.id!!, now).scheduledAt shouldBe now
        val night = Instant.parse("2026-11-03T22:00:00Z")
        planner.scheduleManual(i.id!!, night).scheduledAt shouldBe Instant.parse("2026-11-04T08:30:00Z")
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

    @Test
    fun `a second inquiry of the same phone gets no call of its own while the first one's call is open`() {
        val first = inquiry()
        planner.onInquiryCreated(first)
        val second = inquiry()
        planner.onInquiryCreated(second)
        callsOf(first.id!!).single().status shouldBe CallStatus.SCHEDULED
        val skipped = callsOf(second.id!!).single()
        skipped.status shouldBe CallStatus.SKIPPED
        skipped.skipReason shouldBe SkipReason.SAME_PERSON
    }

    @Test
    fun `a new inquiry within 7 days of a finished confirmation of the same phone gets no call`() {
        call(inquiry(), status = CallStatus.DONE) { endedAt = now.minus(Duration.ofDays(1)) }
        val second = inquiry()
        planner.onInquiryCreated(second)
        callsOf(second.id!!).single().skipReason shouldBe SkipReason.RECENT_CONFIRMATION
    }

    @Test
    fun `a new inquiry of a phone that said do not call is marked too and gets no call`() {
        inquiry { doNotCall = true }
        val second = inquiry()
        planner.onInquiryCreated(second)
        callsOf(second.id!!) shouldBe emptyList()
        inquiries.findById(second.id!!).get().doNotCall shouldBe true
    }

    @Test
    fun `a number whose time zones share no window gets no automatic call`() {
        val i = inquiry(phone = "+79161234567")
        planner.onInquiryCreated(i)
        callsOf(i.id!!).single().skipReason shouldBe SkipReason.TIMEZONE_UNCLEAR
    }

    @Test
    fun `offer sent never cancels a call the dialler has just started`() {
        val i = inquiry()
        val c = call(i, scheduledAt = now)
        val dialling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dialler =
            thread {
                TransactionTemplate(txManager).executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM inquiry_call WHERE id = ? FOR UPDATE", c.id)
                    jdbc.update("UPDATE inquiry_call SET status = 'DIALING', started_at = now() WHERE id = ?", c.id)
                    dialling.countDown()
                    release.await()
                }
            }
        dialling.await()
        val offer = thread { TransactionTemplate(txManager).executeWithoutResult { planner.onOfferSent(i.id!!, "Lagoon 46") } }
        Thread.sleep(500) // the offer has read the row and waits for the dialler's lock
        release.countDown()
        dialler.join()
        offer.join()
        calls.findById(c.id!!).get().status shouldBe CallStatus.DIALING
    }
}
