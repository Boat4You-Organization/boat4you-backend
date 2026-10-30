package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * No call stays "in progress" for ever (design §4): webhook events that were not applied (the call could not be found yet, or
 * processing failed) are retried for 2 days, and calls still DIALING / IN_PROGRESS after 30 minutes are completed from the
 * ElevenLabs conversation API; one older than 2 hours that is still not finished is FAILED as lost and Mario is told. A lost
 * call is never dialled again: it may have rung. One call that fails here never stops the others.
 */
@Service
class CallReconciler(
    private val calls: InquiryCallRepository,
    private val events: CallWebhookEventRepository,
    private val processor: CallWebhookProcessor,
    private val outcomes: CallOutcomeService,
    private val elevenLabs: ElevenLabsApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(now: Instant) {
        events.findUnprocessedBetween(now.minus(EVENT_GIVE_UP), now.minus(EVENT_GRACE)).forEach { processor.process(it.id!!) }
        calls.findStuck(now.minus(STUCK_AFTER)).forEach { call ->
            runCatching { reconcile(call, now) }.onFailure { log.error("Call {} not reconciled", call.id, it) }
        }
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
            old -> {
                log.warn("Call {} lost (no result after {}), marked FAILED", call.id, LOST_AFTER)
                outcomes.applyLost(call)
            }
            else -> Unit
        }
    }

    companion object {
        val EVENT_GRACE: Duration = Duration.ofMinutes(2)
        val EVENT_GIVE_UP: Duration = Duration.ofDays(2)
        val STUCK_AFTER: Duration = Duration.ofMinutes(30)
        val LOST_AFTER: Duration = Duration.ofHours(2)
        private val FINISHED = setOf("done", "failed")
    }
}
