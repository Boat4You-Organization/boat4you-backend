package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationAnalysis
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationMetadata
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.elevenlabs.OutboundCallResult
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.calls.service.CallOutcomeService
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.calls.service.CallReconciler
import hr.workspace.boat4you.domains.calls.service.CallRetention
import hr.workspace.boat4you.domains.calls.service.CallWebhookProcessor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Lost webhooks and old data (design §4 "silent loss", §6.3 retention). */
class CallReconcileTest : CallsJpaTest() {
    @Autowired private lateinit var events: CallWebhookEventRepository

    private val now: Instant = Instant.parse("2026-11-03T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val uploadDir = Files.createTempDirectory("calls-retention")

    private class FakeApi : ElevenLabsApi {
        var status = "done"

        override fun startOutboundCall(
            toNumber: String,
            language: String,
            variables: Map<String, String>,
        ) = OutboundCallResult(null, null)

        override fun getConversation(conversationId: String) =
            ConversationData(conversationId, status, emptyList(), ConversationMetadata(60, 100, "end_call"), ConversationAnalysis("success", "Sažetak."))
    }

    private val api = FakeApi()
    private val planner by lazy { CallPlanner(calls, inquiries, settings, jdbc, clock) }
    private val audio by lazy { CallAudioStore(uploadDir.toString()) }
    private val notifier: CallNotifier = mock(CallNotifier::class.java)
    private val outcomes by lazy { CallOutcomeService(calls, inquiries, planner, notifier, audio, clock) }
    private val processor by lazy { CallWebhookProcessor(events, calls, outcomes, txManager, jacksonObjectMapper()) }
    private val reconciler by lazy { CallReconciler(calls, events, processor, outcomes, api) }
    private val retention by lazy { CallRetention(calls, audio, settings, jdbc) }

    @BeforeEach
    fun resetApi() {
        api.status = "done"
    }

    private fun stuck(
        startedAgo: Duration,
        conversation: String? = "conv-${System.nanoTime()}",
    ) = call(inquiry(), status = CallStatus.DIALING) {
        startedAt = now.minus(startedAgo)
        elevenlabsConversationId = conversation
    }

    @Test
    fun `a call stuck in DIALING for 31 minutes is completed from the conversation API`() {
        val c = stuck(Duration.ofMinutes(31))
        reconciler.runOnce(now)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DONE
        saved.outcome shouldBe CallOutcome.CONFIRMED
        saved.summary shouldBe "Sažetak."
    }

    @Test
    fun `a call still running and younger than 2 h is left alone`() {
        api.status = "in-progress"
        val c = stuck(Duration.ofMinutes(40))
        reconciler.runOnce(now)
        calls.findById(c.id!!).get().status shouldBe CallStatus.DIALING
    }

    @Test
    fun `a lost call older than 2 h becomes FAILED, is not dialled again (it may have rung) and Mario is told`() {
        clearInvocations(notifier)
        api.status = "in-progress"
        val c = stuck(Duration.ofHours(3))
        reconciler.runOnce(now)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.FAILED
        saved.error shouldBe "lost"
        calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).count { it.status == CallStatus.SCHEDULED } shouldBe 0
        verify(notifier, times(1)).lost(any() ?: c, any() ?: inquiries.findById(c.inquiryId!!).get())
    }

    @Test
    fun `an unprocessed webhook event is processed on the next run`() {
        val c = stuck(Duration.ofMinutes(5), conversation = "conv-late")
        val body = """{"type":"call_initiation_failure","data":{"conversation_id":"conv-late","failure_reason":"no-answer"}}"""
        val id = processor.store("conv-late", "call_initiation_failure", body)!!
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minusSeconds(300)), id)
        reconciler.runOnce(now)
        events.findById(id).get().processedAt.shouldNotBeNull()
        calls.findById(c.id!!).get().status shouldBe CallStatus.NO_ANSWER
    }

    @Test
    fun `audio older than the retention is deleted and the path cleared`() {
        val old = call(inquiry(), status = CallStatus.DONE) { endedAt = now.minus(Duration.ofDays(31)) }
        old.audioPath = audio.save("conv-old", byteArrayOf(1), now)
        calls.save(old)
        val fresh = call(inquiry(), status = CallStatus.DONE) { endedAt = now.minus(Duration.ofDays(2)) }
        fresh.audioPath = audio.save("conv-fresh", byteArrayOf(2), now)
        calls.save(fresh)
        retention.runOnce(now)
        calls.findById(old.id!!).get().audioPath.shouldBeNull()
        Files.exists(audio.resolve("calls/2026/11/conv-old.mp3")) shouldBe false
        calls.findById(fresh.id!!).get().audioPath.shouldNotBeNull()
    }

    @Test
    fun `webhook events older than 90 days are deleted`() {
        val oldId = processor.store("c-old", "post_call_audio", """{"type":"post_call_audio","data":{}}""")!!
        val newId = processor.store("c-new", "post_call_audio", """{"type":"post_call_audio","data":{}}""")!!
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(91))), oldId)
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(10))), newId)
        retention.runOnce(now)
        events.findById(oldId).isPresent shouldBe false
        events.findById(newId).isPresent shouldBe true
    }

    @Test
    fun `a webhook for a call whose start was unclear is matched by the call id it carries`() {
        val c = stuck(Duration.ofMinutes(1), conversation = null)
        val body =
            """{"type":"post_call_transcription","data":{"conversation_id":"conv-unclear","status":"done","transcript":[],""" +
                """"metadata":{"call_duration_secs":60},"analysis":{"call_successful":"success"},""" +
                """"conversation_initiation_client_data":{"dynamic_variables":{"call_id":"${c.id}"}}}}"""
        val id = processor.store("conv-unclear", "post_call_transcription", body)!!
        processor.process(id)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DONE
        saved.elevenlabsConversationId shouldBe "conv-unclear"
    }

    @Test
    fun `unprocessed webhook events older than 2 days are not loaded again`() {
        val oldId = processor.store("c-2d-old", "post_call_audio", """{"type":"post_call_audio","data":{}}""")!!
        val newId = processor.store("c-2d-new", "post_call_audio", """{"type":"post_call_audio","data":{}}""")!!
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(3))), oldId)
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofMinutes(10))), newId)
        events.findUnprocessedBetween(now.minus(Duration.ofDays(2)), now.minus(Duration.ofMinutes(2))).map { it.id } shouldBe listOf(newId)
    }

    @Test
    fun `a processed recording is not kept in the stored event`() {
        val c = stuck(Duration.ofMinutes(1), conversation = "conv-audio-strip")
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val id = processor.store("conv-audio-strip", "post_call_audio", """{"type":"post_call_audio","data":{"conversation_id":"conv-audio-strip","full_audio":"$b64"}}""")!!
        processor.process(id)
        calls.findById(c.id!!).get().audioPath.shouldNotBeNull()
        events.findById(id).get().payload!! shouldNotContain "full_audio"
    }

    @Test
    fun `an unprocessed recording older than 2 days is stripped by retention`() {
        val id = processor.store("c-orphan-audio", "post_call_audio", """{"type":"post_call_audio","data":{"full_audio":"AAEC"}}""")!!
        jdbc.update("UPDATE call_webhook_event SET received_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(3))), id)
        retention.runOnce(now)
        events.findById(id).get().payload!! shouldNotContain "full_audio"
    }
}
