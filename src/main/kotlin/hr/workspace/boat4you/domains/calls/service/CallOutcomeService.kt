package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Base64

/**
 * What a finished call means (design §2.2-§2.5): stores the result on the call row, then retries, marks "do not call",
 * opens a callback task for Mario and mails him when he is needed. Each method locks and re-reads the row first, so two
 * results of one call (transcription and recording, webhook and reconcile) never overwrite each other, and is idempotent:
 * a call that already left DIALING / IN_PROGRESS is not touched again, so a redelivered webhook changes nothing.
 */
@Service
class CallOutcomeService(
    private val calls: InquiryCallRepository,
    private val inquiries: InquiryRepository,
    private val planner: CallPlanner,
    private val notifier: CallNotifier,
    private val audio: CallAudioStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun applyTranscription(
        row: InquiryCall,
        data: ConversationData,
    ) {
        val call = calls.lockFresh(row.id!!)?.takeIf { it.status in OPEN } ?: return
        val now = clock.instant()
        val inquiry = inquiries.findById(call.inquiryId!!).orElseThrow()
        val collected = data.analysis?.dataCollectionResults.orEmpty().mapValues { it.value.value }
        call.endedAt = now
        call.durationSecs = data.metadata?.callDurationSecs
        call.costCredits = data.metadata?.cost
        call.terminationReason = data.metadata?.terminationReason?.take(200)
        call.summary = data.analysis?.transcriptSummary
        call.data = collected
        call.quality = data.analysis?.evaluationCriteriaResults.orEmpty().mapValues { mapOf("result" to it.value.result, "rationale" to it.value.rationale) }
        call.transcript =
            data.transcript.map { t ->
                mapOf(
                    "role" to t.role,
                    "message" to t.message,
                    "time_in_call_secs" to t.timeInCallSecs,
                    "tools" to t.toolCalls.orEmpty().mapNotNull { it["tool_name"] ?: it["name"] },
                )
            }
        if (call.terminationReason?.contains("voicemail", ignoreCase = true) == true) {
            call.status = CallStatus.VOICEMAIL
            calls.save(call)
            planner.scheduleRetry(call, CallStatus.VOICEMAIL, now)
            return
        }
        call.status = CallStatus.DONE
        call.outcome = outcomeOf(data, collected)
        calls.save(call)

        when (call.outcome) {
            CallOutcome.DO_NOT_CALL -> {
                inquiry.phoneE164?.let(planner::markDoNotCall)
                notifier.outcome(call, inquiry)
            }
            CallOutcome.READY_TO_BOOK, CallOutcome.CALL_BACK, CallOutcome.NO_CONSENT_TO_RECORD -> {
                planner.createHumanTask(inquiry, call, notifierLabel(call.outcome))
                notifier.outcome(call, inquiry)
            }
            CallOutcome.TRANSFERRED -> notifier.outcome(call, inquiry)
            else -> Unit
        }
        if (call.quality.orEmpty().values.any { (it as? Map<*, *>)?.get("result") == "failure" }) notifier.quality(call, inquiry)
    }

    @Transactional
    fun applyInitiationFailure(
        row: InquiryCall,
        reason: String,
    ) {
        val status =
            when (reason.lowercase()) {
                "busy" -> CallStatus.BUSY
                "no-answer" -> CallStatus.NO_ANSWER
                else -> CallStatus.FAILED
            }
        end(row, status, reason)
    }

    /** ElevenLabs certainly did not place the call (refused, rejected, unreachable): FAILED and one retry. */
    @Transactional
    fun applyNotStarted(
        row: InquiryCall,
        error: String,
    ) = end(row, CallStatus.FAILED, error)

    /** No result 2 h after the start: FAILED but never dialled again, since the client may have been called; Mario is told. */
    @Transactional
    fun applyLost(row: InquiryCall) {
        val call = calls.lockFresh(row.id!!)?.takeIf { it.status in OPEN } ?: return
        call.status = CallStatus.FAILED
        call.endedAt = clock.instant()
        call.error = "lost"
        calls.save(call)
        inquiries.findById(call.inquiryId!!).ifPresent { notifier.lost(call, it) }
    }

    @Transactional
    fun applyAudio(
        row: InquiryCall,
        base64Mp3: String,
    ) {
        val call = calls.lockFresh(row.id!!)?.takeIf { it.audioPath == null } ?: return
        val conversation = call.elevenlabsConversationId ?: return
        call.audioPath = audio.save(conversation, Base64.getDecoder().decode(base64Mp3), clock.instant())
        calls.save(call)
    }

    /** A call that ended without a conversation: the retry rules apply; the second technical failure of a call tells Mario (§2.5). */
    private fun end(
        row: InquiryCall,
        status: CallStatus,
        error: String,
    ) {
        val call = calls.lockFresh(row.id!!)?.takeIf { it.status in OPEN } ?: return
        val now = clock.instant()
        val failedBefore = status == CallStatus.FAILED && planner.earlierAttemptFailed(call)
        call.status = status
        call.endedAt = now
        call.error = error.take(500)
        calls.save(call)
        planner.scheduleRetry(call, status, now)
        if (failedBefore) inquiries.findById(call.inquiryId!!).ifPresent { notifier.technicalFailure(call, it) }
    }

    private fun notifierLabel(outcome: CallOutcome?) =
        when (outcome) {
            CallOutcome.READY_TO_BOOK -> "Klijent je spreman rezervirati, prebacivanje nije bilo moguće."
            CallOutcome.CALL_BACK -> "Klijent traži povratni poziv."
            CallOutcome.NO_CONSENT_TO_RECORD -> "Klijent ne želi snimanje; nazovi osobno."
            else -> ""
        }

    companion object {
        val OPEN = setOf(CallStatus.DIALING, CallStatus.IN_PROGRESS)

        /** The outcome from the agent's data collection, in priority order (design §6.5, plan Task 7). */
        fun outcomeOf(
            data: ConversationData,
            collected: Map<String, Any?>,
        ): CallOutcome {
            fun yes(key: String) = collected[key] == true || collected[key]?.toString().equals("true", ignoreCase = true)
            val transferred = data.transcript.any { t -> t.toolCalls.orEmpty().any { (it["tool_name"] ?: it["name"]) == "transfer_to_number" } }
            return when {
                yes("do_not_call") -> CallOutcome.DO_NOT_CALL
                yes("recording_refused") -> CallOutcome.NO_CONSENT_TO_RECORD
                transferred -> CallOutcome.TRANSFERRED
                yes("ready_to_book") -> CallOutcome.READY_TO_BOOK
                !collected["callback_time"]?.toString().isNullOrBlank() -> CallOutcome.CALL_BACK
                yes("booked_elsewhere") -> CallOutcome.BOOKED_ELSEWHERE
                yes("not_interested") -> CallOutcome.NOT_INTERESTED
                yes("wants_changes") -> CallOutcome.WANTS_CHANGES
                data.analysis?.callSuccessful == "success" -> CallOutcome.CONFIRMED
                else -> CallOutcome.UNCLEAR
            }
        }
    }
}
