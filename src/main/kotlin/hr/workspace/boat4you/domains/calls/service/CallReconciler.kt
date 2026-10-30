package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * No call stays "in progress" for ever (design §4): webhook events that were not applied (the call had no conversation id
 * yet, or processing failed) are retried, and calls still DIALING / IN_PROGRESS after 30 minutes are completed from the
 * ElevenLabs conversation API; one older than 2 hours that is still not finished is FAILED as lost and retried once.
 */
@Service
class CallReconciler(
    private val calls: InquiryCallRepository,
    private val events: CallWebhookEventRepository,
    private val processor: CallWebhookProcessor,
    private val outcomes: CallOutcomeService,
    private val elevenLabs: ElevenLabsApi,
    private val planner: CallPlanner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(now: Instant) {
        events
            .findUnprocessedOlderThan(now.minus(EVENT_GRACE))
            .filter { it.receivedAt.isAfter(now.minus(EVENT_GIVE_UP)) }
            .forEach { processor.process(it.id!!) }
        calls.findStuck(now.minus(STUCK_AFTER)).forEach { reconcile(it, now) }
    }

    private fun reconcile(
        call: InquiryCall,
        now: Instant,
    ) {
        val old = call.startedAt?.isBefore(now.minus(LOST_AFTER)) ?: true
        val conversation = call.elevenlabsConversationId
        val data =
            conversation?.let { id ->
                runCatching { elevenLabs.getConversation(id) }.onFailure { log.warn("Conversation of call {} not readable: {}", call.id, it.message) }.getOrNull()
            }
        when {
            data != null && data.status in FINISHED -> outcomes.applyTranscription(call, data)
            old -> lost(call, now)
            else -> Unit
        }
    }

    private fun lost(
        call: InquiryCall,
        now: Instant,
    ) {
        log.warn("Call {} lost (no result after {}), marked FAILED", call.id, LOST_AFTER)
        call.status = CallStatus.FAILED
        call.endedAt = now
        call.error = "lost"
        calls.save(call)
        planner.scheduleRetry(call, CallStatus.FAILED, now)
    }

    companion object {
        val EVENT_GRACE: Duration = Duration.ofMinutes(2)
        val EVENT_GIVE_UP: Duration = Duration.ofDays(2)
        val STUCK_AFTER: Duration = Duration.ofMinutes(30)
        val LOST_AFTER: Duration = Duration.ofHours(2)
        private val FINISHED = setOf("done", "failed")
    }
}
