package hr.workspace.boat4you.domains.calls.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Raw ElevenLabs webhook events (design §6.2): [store] keeps each (conversation, type) once, [process] applies it to its call.
 * An event whose call does not yet carry that conversation id (the webhook beat the dispatcher's commit) stays unprocessed;
 * the reconcile job retries it. A failure is recorded on the event and also retried.
 */
@Service
class CallWebhookProcessor(
    private val events: CallWebhookEventRepository,
    private val calls: InquiryCallRepository,
    private val outcomes: CallOutcomeService,
    txManager: PlatformTransactionManager,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    /** The new event id, or null when this (conversation, type) was already stored. */
    fun store(
        conversationId: String,
        type: String,
        rawBody: String,
    ): Long? = tx.execute { events.insertIfAbsent(conversationId, type, rawBody) }

    @Async
    fun processAsync(eventId: Long) = process(eventId)

    fun process(eventId: Long) {
        try {
            tx.executeWithoutResult {
                val event = events.findById(eventId).orElse(null) ?: return@executeWithoutResult
                if (event.processedAt != null) return@executeWithoutResult
                val call = calls.findByElevenlabsConversationId(event.conversationId!!) ?: return@executeWithoutResult
                val data = objectMapper.readTree(event.payload).path("data")
                when (event.type) {
                    TRANSCRIPTION -> outcomes.applyTranscription(call, objectMapper.treeToValue(data, ConversationData::class.java))
                    AUDIO -> data.path("full_audio").asText(null)?.let { outcomes.applyAudio(call, it) }
                    INITIATION_FAILURE -> outcomes.applyInitiationFailure(call, data.path("failure_reason").asText("unknown"))
                }
                event.processedAt = Instant.now()
                event.error = null
                events.save(event)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Call webhook event {} failed, left for reconcile", eventId, e)
            tx.executeWithoutResult {
                events.findById(eventId).ifPresent {
                    it.error = (e.message ?: e.javaClass.simpleName).take(500)
                    events.save(it)
                }
            }
        }
    }

    companion object {
        const val TRANSCRIPTION = "post_call_transcription"
        const val AUDIO = "post_call_audio"
        const val INITIATION_FAILURE = "call_initiation_failure"
        val TYPES = setOf(TRANSCRIPTION, AUDIO, INITIATION_FAILURE)
    }
}
