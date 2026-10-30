package hr.workspace.boat4you.domains.calls.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.domains.calls.elevenlabs.ConversationData
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Raw ElevenLabs webhook events (design §6.2): [store] keeps each (conversation, type) once, [process] applies it to its call,
 * found by conversation id or else by the call id Nika carries. An event whose call cannot be found yet (the webhook beat the
 * dispatcher's commit) stays unprocessed; the reconcile job retries it. A failure is recorded on the event and also retried.
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
                val data = objectMapper.readTree(event.payload).path("data")
                val call =
                    calls.findByElevenlabsConversationId(event.conversationId!!)
                        ?: byCallId(data, event.conversationId!!)
                        ?: return@executeWithoutResult
                when (event.type) {
                    TRANSCRIPTION -> outcomes.applyTranscription(call, objectMapper.treeToValue(data, ConversationData::class.java))
                    AUDIO -> {
                        data.path("full_audio").asText(null)?.let { outcomes.applyAudio(call, it) }
                        // The recording lives in its file (with its own retention), never in this table.
                        event.payload = withoutAudio(event.payload!!)
                    }
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

    /**
     * A call whose start was unclear has no conversation id: ElevenLabs echoes the `call_id` dynamic variable we passed, so the
     * event still finds its row, which then gets the conversation id.
     */
    private fun byCallId(
        data: JsonNode,
        conversationId: String,
    ): InquiryCall? {
        val id = data.path("conversation_initiation_client_data").path("dynamic_variables").path("call_id").asText("").toLongOrNull() ?: return null
        val call = calls.lockFresh(id)?.takeIf { it.elevenlabsConversationId == null } ?: return null
        call.elevenlabsConversationId = conversationId
        return calls.saveAndFlush(call)
    }

    private fun withoutAudio(payload: String): String {
        val tree = objectMapper.readTree(payload)
        (tree.path("data") as? ObjectNode)?.remove("full_audio")
        return objectMapper.writeValueAsString(tree)
    }

    companion object {
        const val TRANSCRIPTION = "post_call_transcription"
        const val AUDIO = "post_call_audio"
        const val INITIATION_FAILURE = "call_initiation_failure"
        val TYPES = setOf(TRANSCRIPTION, AUDIO, INITIATION_FAILURE)
    }
}
