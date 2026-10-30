package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationAnalysis
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationMetadata
import hr.workspace.boat4you.domains.calls.elevenlabs.CriterionResult
import hr.workspace.boat4you.domains.calls.elevenlabs.DataCollectionResult
import hr.workspace.boat4you.domains.calls.elevenlabs.TranscriptTurn
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.calls.service.CallOutcomeService
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Call results from ElevenLabs into our rows (design §2.2-§2.5, §6.2). 3.11.2026 09:00 UTC = Tuesday 10:00 Zagreb. */
class CallOutcomeServiceTest : CallsJpaTest() {
    private val uploadDir: Path = Files.createTempDirectory("calls-audio")

    private val now: Instant = Instant.parse("2026-11-03T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val notifier: CallNotifier = mock(CallNotifier::class.java)
    private val planner by lazy { CallPlanner(calls, inquiries, settings, jdbc, clock) }
    private val audio by lazy { CallAudioStore(uploadDir.toString()) }
    private val service by lazy { CallOutcomeService(calls, inquiries, planner, notifier, audio, clock) }

    @BeforeEach
    fun resetNotifier() = clearInvocations(notifier)

    private fun dialling(kind: CallKind = CallKind.CONFIRMATION): InquiryCall {
        val i = inquiry { name = "Ana" }
        return call(i, kind = kind, status = CallStatus.DIALING) {
            startedAt = now.minusSeconds(200)
            elevenlabsConversationId = "conv-${System.nanoTime()}"
        }
    }

    private fun data(
        collected: Map<String, Any?> = emptyMap(),
        success: String = "success",
        termination: String = "end_call tool was called",
        tools: List<String> = emptyList(),
        quality: Map<String, String> = mapOf("ai_disclosed" to "success"),
    ) = ConversationData(
        conversationId = "x",
        status = "done",
        transcript = listOf(TranscriptTurn("agent", "Dobar dan", 0, tools.map { mapOf("tool_name" to it) })),
        metadata = ConversationMetadata(184, 512, termination),
        analysis =
            ConversationAnalysis(
                callSuccessful = success,
                transcriptSummary = "Sažetak.",
                dataCollectionResults = collected.mapValues { DataCollectionResult(it.value) },
                evaluationCriteriaResults = quality.mapValues { CriterionResult(it.value, "r") },
            ),
    )

    private fun outcomeOf(d: ConversationData): CallOutcome? {
        val c = dialling()
        service.applyTranscription(c, d)
        return calls.findById(c.id!!).get().outcome
    }

    @Test
    fun `a webhook fixture is stored with status, summary, data, quality and transcript`() {
        val webhook: Map<String, Any?> = jacksonObjectMapper().readValue(javaClass.getResource("/calls/post_call_transcription.json")!!)
        val d: ConversationData = jacksonObjectMapper().convertValue(webhook["data"], ConversationData::class.java)
        val c = dialling()
        service.applyTranscription(c, d)
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DONE
        saved.outcome shouldBe CallOutcome.CONFIRMED
        saved.durationSecs shouldBe 184
        saved.costCredits shouldBe 512
        saved.summary shouldBe "Klijentica potvrdila 8 osoba, katamaran, 12.-19.6.2027."
        saved.data!!["people"] shouldBe 8
        saved.quality!!["ai_disclosed"] shouldBe mapOf("result" to "success", "rationale" to "Said she is an AI.")
        saved.transcript!!.size shouldBe 3
        saved.endedAt shouldBe now
    }

    @Test
    fun `outcome priority follows the collected data`() {
        outcomeOf(data(mapOf("do_not_call" to true, "ready_to_book" to true))) shouldBe CallOutcome.DO_NOT_CALL
        outcomeOf(data(mapOf("recording_refused" to true))) shouldBe CallOutcome.NO_CONSENT_TO_RECORD
        outcomeOf(data(mapOf("ready_to_book" to true), tools = listOf("transfer_to_number"))) shouldBe CallOutcome.TRANSFERRED
        outcomeOf(data(mapOf("ready_to_book" to true))) shouldBe CallOutcome.READY_TO_BOOK
        outcomeOf(data(mapOf("callback_time" to "sutra u 10"))) shouldBe CallOutcome.CALL_BACK
        outcomeOf(data(mapOf("booked_elsewhere" to true))) shouldBe CallOutcome.BOOKED_ELSEWHERE
        outcomeOf(data(mapOf("not_interested" to true))) shouldBe CallOutcome.NOT_INTERESTED
        outcomeOf(data(mapOf("wants_changes" to true))) shouldBe CallOutcome.WANTS_CHANGES
        outcomeOf(data()) shouldBe CallOutcome.CONFIRMED
        outcomeOf(data(success = "failure")) shouldBe CallOutcome.UNCLEAR
    }

    @Test
    fun `do not call marks every inquiry of that phone and cancels their scheduled calls`() {
        val c = dialling()
        val other = inquiry()
        val pending = call(other, scheduledAt = now.plusSeconds(3600))
        service.applyTranscription(c, data(mapOf("do_not_call" to true)))
        inquiries.findById(other.id!!).get().doNotCall shouldBe true
        calls.findById(pending.id!!).get().status shouldBe CallStatus.CANCELLED
        verify(notifier).outcome(any(InquiryCall::class.java) ?: c, any() ?: inquiries.findAll().first())
    }

    @Test
    fun `ready to book without a transfer creates a callback task for Mario`() {
        val c = dialling()
        service.applyTranscription(c, data(mapOf("ready_to_book" to true)))
        val tasks = calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).filter { it.kind == CallKind.HUMAN_CALLBACK }
        tasks.size shouldBe 1
        tasks.single().status shouldBe CallStatus.SCHEDULED
    }

    @Test
    fun `voicemail is not an answer and is retried 2 hours later`() {
        val c = dialling()
        service.applyTranscription(c, data(termination = "Voicemail detected"))
        calls.findById(c.id!!).get().status shouldBe CallStatus.VOICEMAIL
        calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).first { it.status == CallStatus.SCHEDULED }.scheduledAt shouldBe
            Instant.parse("2026-11-03T11:00:00Z")
    }

    @Test
    fun `a busy initiation failure schedules a retry 30 minutes later`() {
        val c = dialling()
        service.applyInitiationFailure(c, "busy")
        calls.findById(c.id!!).get().status shouldBe CallStatus.BUSY
        calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).first { it.status == CallStatus.SCHEDULED }.scheduledAt shouldBe
            Instant.parse("2026-11-03T09:30:00Z")
    }

    @Test
    fun `the third no-answer schedules nothing more`() {
        val c = dialling()
        c.attempt = 3
        calls.save(c)
        service.applyInitiationFailure(c, "no-answer")
        calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).filter { it.status == CallStatus.SCHEDULED }.shouldBeEmpty()
    }

    @Test
    fun `a second delivery of the same result changes nothing and notifies once`() {
        val c = dialling()
        service.applyTranscription(c, data(mapOf("ready_to_book" to true)))
        service.applyTranscription(calls.findById(c.id!!).get(), data(mapOf("ready_to_book" to true)))
        calls.findByInquiryIdOrderByCreatedAtDesc(c.inquiryId!!).count { it.kind == CallKind.HUMAN_CALLBACK } shouldBe 1
        verify(notifier, times(1)).outcome(any(InquiryCall::class.java) ?: c, any() ?: inquiries.findAll().first())
    }

    @Test
    fun `a failed quality criterion is reported`() {
        service.applyTranscription(dialling(), data(quality = mapOf("ai_disclosed" to "failure")))
        verify(notifier).quality(any(InquiryCall::class.java) ?: InquiryCall(), any() ?: inquiries.findAll().first())
    }

    @Test
    fun `audio is written under calls yyyy mm and the path stored`() {
        val c = dialling()
        service.applyAudio(c, Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        val path = calls.findById(c.id!!).get().audioPath.shouldNotBeNull()
        path shouldMatch Regex("calls/2026/11/conv-[0-9]+\\.mp3")
        Files.readAllBytes(uploadDir.resolve(path)) shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun `a confirmed call notifies nobody`() {
        service.applyTranscription(dialling(), data())
        verify(notifier, never()).outcome(any(InquiryCall::class.java) ?: InquiryCall(), any() ?: inquiries.findAll().first())
    }

    @Test
    fun `a transcription and the recording of one call processed at the same time both stick`() {
        val c = dialling()
        val mp3 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val applied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tx = TransactionTemplate(txManager)
        val transcription =
            thread {
                tx.executeWithoutResult {
                    service.applyTranscription(calls.findById(c.id!!).get(), data())
                    applied.countDown()
                    release.await()
                }
            }
        applied.await()
        val recording = thread { tx.executeWithoutResult { service.applyAudio(calls.findById(c.id!!).get(), mp3) } }
        recording.join(1000) // before the fix it is done here; with the row lock it waits for the transcription
        release.countDown()
        transcription.join()
        recording.join()
        val saved = calls.findById(c.id!!).get()
        saved.status shouldBe CallStatus.DONE
        saved.audioPath.shouldNotBeNull()
    }

    @Test
    fun `a second technical start failure reported by webhook tells Mario`() {
        val i = inquiry { name = "Ana" }
        call(i, status = CallStatus.FAILED, attempt = 1) { endedAt = now.minusSeconds(900) }
        val second =
            call(i, status = CallStatus.DIALING, attempt = 2) {
                startedAt = now.minusSeconds(30)
                elevenlabsConversationId = "conv-tf-${System.nanoTime()}"
            }
        service.applyInitiationFailure(second, "unknown")
        verify(notifier, times(1)).technicalFailure(any() ?: second, any() ?: i)
    }
}
