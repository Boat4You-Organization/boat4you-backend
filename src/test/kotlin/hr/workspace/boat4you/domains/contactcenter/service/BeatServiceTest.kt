package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

class BeatServiceTest {
    private val clock = CcTestClock()
    private val registry = LiveVisitorRegistry().also { it.clock = clock }
    private val writer = TrackerWriteQueue(JdbcTemplate(), Mockito.mock(PlatformTransactionManager::class.java))
    private val visits = Mockito.mock(VisitService::class.java)
    private val proactive = Mockito.mock(ProactiveMessageService::class.java)
    private val service = BeatService(visits, registry, writer, proactive).also { it.clock = clock }
    private val entry = liveEntry(visitId = 9, lastBeatAt = clock.now).also { registry.register(it) }

    private fun pv(
        id: String,
        path: String = "/boat/x-1",
    ) = PageViewIn(id, "https://www.boat4you.com$path", path, "X", "BOAT", "x-1", clock.now)

    private fun cmd(
        pvs: List<PageViewIn> = emptyList(),
        evs: List<EventIn> = emptyList(),
    ) = BeatCommand(entry.siteId, entry.visitorKey, entry.visitKey, true, pvs, evs)

    @BeforeEach
    fun stub() {
        Mockito.`when`(visits.resolveForBeat(entry.visitKey, entry.visitorKey, entry.siteId)).thenReturn(entry)
    }

    @Test
    fun `unknown visit, live state, rows and throttled touch`() {
        service.beat(BeatCommand("boat4you", entry.visitorKey, "c0000000-0000-4000-8000-00000000000c")) shouldBe BeatService.BeatOutcome.UnknownVisit
        service.beat(cmd(listOf(pv("pv1-abcdef")))) shouldBe BeatService.BeatOutcome.Quiet
        registry.liveSnapshot().single().let {
            it.currentPath shouldBe "/boat/x-1"
            it.pageType shouldBe "BOAT"
            it.pageCount shouldBe 1
        }
        writer.queuedRows().size shouldBe 1
        writer.pendingTouches() shouldBe emptyMap() // < 15 s since the visit started
        clock.advance(Duration.ofSeconds(16))
        service.beat(cmd())
        writer.pendingTouches()[9L] shouldBe VisitTouch(clock.now, null)
    }

    @Test
    fun `31 distinct page views in a minute flag the visit as a bot`() {
        service.beat(cmd((1..20).map { pv("pv-%06d".format(it)) }))
        service.beat(cmd((21..31).map { pv("pv-%06d".format(it)) }))
        entry.bot shouldBe true
        writer.pendingTouches()[9L]!!.botReason shouldBe BotFilter.PV_RATE
        registry.liveSnapshot() shouldBe emptyList()
        registry.drainBotCounts() shouldBe mapOf(entry.siteId to 1) // BOT_BURST input (Task 12b)
    }

    @Test
    fun `consent withdrawn removes the visitor from the live list at once`() {
        service.beat(cmd(evs = listOf(EventIn("ev1-abcdef", "CONSENT", null, null, null, null, null, true, clock.now)))) shouldBe BeatService.BeatOutcome.Quiet
        registry.get(entry.visitKey) shouldBe null
    }

    @Test
    fun `pending proactive messages are delivered in the beat`() {
        val d = ProactiveDelivery("tok", listOf(DeliveredMessage(1, "Bok", clock.now)))
        Mockito.`when`(proactive.deliver(entry)).thenReturn(d)
        service.beat(cmd()) shouldBe BeatService.BeatOutcome.Delivered(d)
    }

    // The tests below pin behaviour of the beat that the four above leave open.

    @Test
    fun `the live entry follows the beat - visibility, last beat, and since when it is on the page`() {
        val t0 = clock.now
        clock.advance(Duration.ofSeconds(5))
        service.beat(BeatCommand(entry.siteId, entry.visitorKey, entry.visitKey, false, listOf(pv("pv1-abcdef", "/a").copy(viewedAt = t0.plusSeconds(2)))))
        entry.visible shouldBe false
        entry.lastBeatAt shouldBe t0.plusSeconds(5)
        entry.currentPath shouldBe "/a"
        entry.currentPageSince shouldBe t0.plusSeconds(2)
        clock.advance(Duration.ofSeconds(5))
        service.beat(cmd(listOf(pv("pv2-abcdef", "/a").copy(viewedAt = t0.plusSeconds(8))))) // the same path: still on it since +2 s
        entry.visible shouldBe true
        entry.currentPageSince shouldBe t0.plusSeconds(2)
        clock.advance(Duration.ofSeconds(5))
        service.beat(cmd(listOf(pv("pv3-abcdef", "/b").copy(viewedAt = t0.plusSeconds(12)), pv("pv4-abcdef", "/a").copy(viewedAt = t0.plusSeconds(11)))))
        entry.currentPath shouldBe "/b" // the latest by viewedAt wins, not the last in the list
        entry.currentPageSince shouldBe t0.plusSeconds(12)
        entry.pageCount shouldBe 4
    }

    @Test
    fun `a retried page view is counted once and every page view and event is queued under the visit id`() {
        val view = pv("pv1-abcdef")
        val click = EventIn("ev1-abcdef", "CLICK", "pv1-abcdef", "Book", null, null, null, false, clock.now)
        service.beat(cmd(listOf(view), listOf(click)))
        service.beat(cmd(listOf(view))) // the widget retried the beat
        entry.pageCount shouldBe 1
        writer.queuedRows() shouldBe listOf(PageViewRow(9, view), EventRow(9, entry.siteId, click), PageViewRow(9, view)) // the writer dedupes them by client id
    }

    @Test
    fun `last_seen_at is written every 15 seconds and not before`() {
        clock.advance(Duration.ofSeconds(14))
        service.beat(cmd())
        writer.pendingTouches() shouldBe emptyMap()
        clock.advance(Duration.ofSeconds(1))
        service.beat(cmd())
        writer.pendingTouches()[9L] shouldBe VisitTouch(clock.now, null)
        val first = clock.now
        clock.advance(Duration.ofSeconds(14))
        service.beat(cmd())
        writer.pendingTouches()[9L] shouldBe VisitTouch(first, null) // throttled again
        clock.advance(Duration.ofSeconds(1))
        service.beat(cmd())
        writer.pendingTouches()[9L] shouldBe VisitTouch(clock.now, null)
    }

    @Test
    fun `a bot is never handed a message, and a bot flagged by page view rate is counted once`() {
        val d = ProactiveDelivery("tok", listOf(DeliveredMessage(1, "Bok", clock.now)))
        Mockito.`when`(proactive.deliver(entry)).thenReturn(d)
        service.beat(cmd((1..31).map { pv("pv-%06d".format(it)) })) shouldBe BeatService.BeatOutcome.Quiet
        service.beat(cmd((32..62).map { pv("pv-%06d".format(it)) })) shouldBe BeatService.BeatOutcome.Quiet
        registry.drainBotCounts() shouldBe mapOf(entry.siteId to 1)
        writer.pendingTouches()[9L]!!.botReason shouldBe BotFilter.PV_RATE
    }

    @Test
    fun `a visit flagged as a bot at the visit call gets no delivery either`() {
        val d = ProactiveDelivery("tok", listOf(DeliveredMessage(1, "Bok", clock.now)))
        Mockito.`when`(proactive.deliver(entry)).thenReturn(d)
        entry.bot = true
        service.beat(cmd()) shouldBe BeatService.BeatOutcome.Quiet
    }

    @Test
    fun `a withdrawn consent queues nothing of the beat but still touches the visit`() {
        val withdrawn = EventIn("ev1-abcdef", "CONSENT", null, null, null, null, null, true, clock.now)
        service.beat(cmd(listOf(pv("pv1-abcdef")), listOf(withdrawn))) shouldBe BeatService.BeatOutcome.Quiet
        writer.queuedRows() shouldBe emptyList()
        writer.pendingTouches()[9L] shouldBe VisitTouch(clock.now, null)
    }
}
