package hr.workspace.boat4you.domains.calls.elevenlabs

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** ElevenLabs access (env on cusma2/cusma3, read at JVM start). Blank key, agent or number = not configured, nothing is called. */
@Component
class ElevenLabsProperties(
    @Value("\${calls.elevenlabs.base-url:https://api.elevenlabs.io}") val baseUrl: String,
    @Value("\${calls.elevenlabs.api-key:}") val apiKey: String,
    @Value("\${calls.elevenlabs.agent-id:}") val agentId: String,
    @Value("\${calls.elevenlabs.phone-number-id:}") val phoneNumberId: String,
    @Value("\${calls.elevenlabs.webhook-secret:}") val webhookSecret: String,
) {
    val configured: Boolean get() = apiKey.isNotBlank() && agentId.isNotBlank() && phoneNumberId.isNotBlank()
}
