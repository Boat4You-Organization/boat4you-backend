package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.elevenlabs.CallNotStartedException
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.elevenlabs.OutboundCallResult
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallDispatcher
import hr.workspace.boat4you.domains.calls.service.CallEligibility
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.calls.service.CallOutcomeService
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The dialler on the scheduler node (design §6.2): rules again right before dialling, once per call, retries on errors. */
class CallDispatcherTest : CallsJpaTest() {
    private val now: Instant = Instant.parse("2026-11-03T09:00:00Z") // Tuesday 10:00 Zagreb

    private class FakeElevenLabs : ElevenLabsApi {
        val dialled = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        var failure: RuntimeException? = null
        val attempts = AtomicInteger()
        private val n = AtomicInteger()

        override fun startOutboundCall(
            toNumber: String,
            language: String,
            variables: Map<String, String>,
        ): OutboundCallResult {
            attempts.incrementAndGet()
            failure?.let { throw it }
            dialled += toNumber to variables
            val i = n.incrementAndGet()
            return OutboundCallResult("conv-$i", "CA$i")
        }

        override fun getConversation(conversationId: String) = ConversationData()
    }

    private val api = FakeElevenLabs()
    private val notifier: CallNotifier = mock(CallNotifier::class.java)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dispatcher by lazy {
        val outcomes =
            CallOutcomeService(
                calls,
                inquiries,
                CallPlanner(calls, inquiries, settings, jdbc, clock),
                notifier,
                CallAudioStore(Files.createTempDirectory("dispatch").toString()),
                clock,
            )
        CallDispatcher(calls, inquiries, CallEligibility(calls, jdbc), outcomes, api, settings, notifier, txManager)
    }

    @BeforeEach
    fun reset() {
        api.dialled.clear()
        api.failure = null
        api.attempts.set(0)
        clearInvocations(notifier)
        settings.write(mapOf("calls.enabled" to "true"))
    }

    @Test
    fun `nothing is dialled while calls are disabled`() {
        settings.write(mapOf("calls.enabled" to "false"))
        val c = call(inquiry(), scheduledAt = now)
        dispatcher.runOnce(now) shouldBe 0
        api.dialled.size shouldBe 0
        calls.findById(c.id!!).get().status shouldBe CallStatus.SCHEDULED
    }

    @Test
    fun `a due confirmation inside the window is dialled once and stored as DIALING with the conversation id`() {
        val c = call(inquiry { name = "Ana" }, scheduledAt = now.minusSeconds(30))
        dispatcher.runOnce(now) shouldBe 1
        dispatcher.runOnce(now) shouldBe 0
        api.dialled.single().first shouldBe "+385912345678"
        api.dialled.single().second["client_name"] shouldBe "Ana"
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DIALING
        saved.startedAt shouldBe now
        saved.elevenlabsConversationId shouldBe "conv-1"
        saved.twilioCallSid shouldBe "CA1"
    }

    @Test
    fun `a due call outside the client's window is moved to the next slot, not dialled`() {
        val c = call(inquiry(phone = "+12125550100"), scheduledAt = now) // 04:00 in New York
        dispatcher.runOnce(now) shouldBe 0
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.SCHEDULED
        saved.scheduledAt shouldBe Instant.parse("2026-11-03T14:30:00Z")
    }

    @Test
    fun `a call the rules reject is SKIPPED with its reason and never dialled`() {
        val c = call(inquiry { doNotCall = true }, scheduledAt = now)
        dispatcher.runOnce(now) shouldBe 0
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.SKIPPED
        saved.skipReason shouldBe SkipReason.DO_NOT_CALL
        api.dialled.size shouldBe 0
    }

    @Test
    fun `a call ElevenLabs certainly did not place is FAILED and retried exactly once 15 minutes later`() {
        api.failure = CallNotStartedException("ElevenLabs POST answered 422")
        val i = inquiry()
        val c = call(i, scheduledAt = now)
        dispatcher.runOnce(now)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.FAILED
        saved.error shouldBe "ElevenLabs POST answered 422"
        val retry = calls.findByInquiryIdOrderByCreatedAtDesc(i.id!!).single { it.status == CallStatus.SCHEDULED }
        retry.scheduledAt shouldBe Instant.parse("2026-11-03T09:15:00Z")
        retry.attempt shouldBe 2
        dispatcher.runOnce(retry.scheduledAt!!) // second technical failure: no third try, Mario is told
        calls.findByInquiryIdOrderByCreatedAtDesc(i.id!!).count { it.status == CallStatus.SCHEDULED } shouldBe 0
        verify(notifier, times(1)).technicalFailure(any() ?: c, any() ?: i)
    }

    @Test
    fun `two dispatchers running at once dial each call once`() {
        repeat(4) { call(inquiry(phone = "+38598111111$it"), scheduledAt = now) }
        val a = CompletableFuture.supplyAsync { dispatcher.runOnce(now) }
        val b = CompletableFuture.supplyAsync { dispatcher.runOnce(now) }
        (a.get() + b.get()) shouldBe 4
        api.dialled.map { it.first }.toSet().size shouldBe 4
        api.dialled.size shouldBe 4
    }

    @Test
    fun `the daily limit stops dialling and sends one notice per day`() {
        settings.write(mapOf("calls.limit.daily" to "1"))
        val first = call(inquiry(phone = "+385981111110"), scheduledAt = now)
        val second = call(inquiry(phone = "+385981111111"), scheduledAt = now)
        dispatcher.runOnce(now)
        dispatcher.runOnce(now)
        api.dialled.size shouldBe 1
        listOf(first, second).map { calls.findById(it.id!!).get().status }.toSet() shouldBe setOf(CallStatus.DIALING, CallStatus.SCHEDULED)
        verify(notifier, times(2)).limitReached(LocalDate.of(2026, 11, 3)) // the notifier itself mails once per day
        settings.write(mapOf("calls.limit.daily" to "40"))
    }

    @Test
    fun `when it is unclear whether the call was placed it stays DIALING and is never dialled again`() {
        api.failure = IllegalStateException("ElevenLabs POST answered 502")
        val i = inquiry()
        val c = call(i, scheduledAt = now)
        dispatcher.runOnce(now)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DIALING
        saved.error!! shouldContain "unclear"
        calls.findByInquiryIdOrderByCreatedAtDesc(i.id!!).size shouldBe 1
        api.failure = null
        dispatcher.runOnce(now.plus(Duration.ofHours(1))) shouldBe 0
        api.attempts.get() shouldBe 1
    }

    @Test
    fun `the call carries its own id, so a webhook can find it without a conversation id`() {
        val c = call(inquiry(), scheduledAt = now)
        dispatcher.runOnce(now)
        api.dialled.single().second["call_id"] shouldBe c.id.toString()
    }

    @Test
    fun `two due inquiries of the same phone ring once, and Nika hears of the other one`() {
        val first = inquiry()
        val second =
            inquiry {
                dateFrom = LocalDate.of(2027, 6, 12)
                dateTo = LocalDate.of(2027, 6, 19)
            }
        call(first, scheduledAt = now)
        val b = call(second, scheduledAt = now)
        dispatcher.runOnce(now) shouldBe 1
        calls.findById(b.id!!).get().skipReason shouldBe SkipReason.SAME_PERSON
        api.dialled.single().second["other_inquiries"]!! shouldContain "2027-06-12"
    }

    @Test
    fun `a call waits 30 minutes while another call to the same phone is in progress`() {
        call(inquiry(), status = CallStatus.DIALING) { startedAt = now.minusSeconds(60) }
        val manual = call(inquiry(), kind = CallKind.MANUAL, scheduledAt = now)
        dispatcher.runOnce(now) shouldBe 0
        val saved = calls.findById(manual.id!!).get()
        saved.status shouldBe CallStatus.SCHEDULED
        saved.scheduledAt shouldBe now.plus(Duration.ofMinutes(30))
    }

    @Test
    fun `one broken call does not stop the others`() {
        val broken = call(inquiry(phone = "+385981111110"), scheduledAt = now)
        call(inquiry(phone = "+385981111111"), scheduledAt = now)
        jdbc.execute("CREATE FUNCTION calls_test_break() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'broken row'; END \$\$")
        jdbc.execute(
            "CREATE TRIGGER calls_test_break BEFORE UPDATE ON inquiry_call FOR EACH ROW " +
                "WHEN (NEW.id = ${broken.id} AND NEW.status = 'DIALING') EXECUTE FUNCTION calls_test_break()",
        )
        try {
            dispatcher.runOnce(now) shouldBe 1
        } finally {
            jdbc.execute("DROP TRIGGER calls_test_break ON inquiry_call")
            jdbc.execute("DROP FUNCTION calls_test_break()")
        }
        api.dialled.single().first shouldBe "+385981111111"
        calls.findById(broken.id!!).get().status shouldBe CallStatus.FAILED
    }
}
