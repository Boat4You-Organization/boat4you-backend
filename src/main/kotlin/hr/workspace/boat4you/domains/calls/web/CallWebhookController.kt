package hr.workspace.boat4you.domains.calls.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsProperties
import hr.workspace.boat4you.domains.calls.service.CallWebhookProcessor
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * ElevenLabs post-call webhooks (design §6.2, §6.6). Verifies the HMAC, stores the raw event once and answers 200 at once;
 * the event is applied asynchronously. Never logs the body (personal data). Public (permitAll), like /webhooks/stripe.
 */
@RestController
@RequestMapping("/webhooks/elevenlabs")
class CallWebhookController(
    private val props: ElevenLabsProperties,
    private val processor: CallWebhookProcessor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = jacksonObjectMapper()

    @PostMapping
    fun receive(
        @RequestBody body: String,
        @RequestHeader("ElevenLabs-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        if (!ElevenLabsSignature.verify(signature, body, props.webhookSecret, clock.instant())) {
            log.warn("ElevenLabs webhook refused: bad or missing signature")
            return ResponseEntity.status(401).build()
        }
        val root = runCatching { json.readTree(body) }.getOrNull() ?: return ResponseEntity.badRequest().build()
        val type = root.path("type").asText("")
        val conversationId = root.path("data").path("conversation_id").asText("")
        if (type !in CallWebhookProcessor.TYPES || conversationId.isBlank()) return ResponseEntity.ok().build()
        processor.store(conversationId, type, body)?.let(processor::processAsync)
        return ResponseEntity.ok().build()
    }
}
