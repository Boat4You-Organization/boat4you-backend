package hr.workspace.boat4you.domains.calls.elevenlabs

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Duration

/**
 * ElevenLabs Agents REST client (docs 30.10.2026): `POST /v1/convai/twilio/outbound-call` with `agent_id`,
 * `agent_phone_number_id`, `to_number` and `conversation_initiation_client_data` {`dynamic_variables`,
 * `conversation_config_override.agent.language`}, answered by {`success`, `message`, `conversation_id`, `callSid`};
 * `GET /v1/convai/conversations/{id}`. Auth header `xi-api-key`. Timeouts 10 s connect / 30 s read, no retries.
 * Request and response bodies are never logged (personal data).
 */
@Component
class ElevenLabsClient(
    private val props: ElevenLabsProperties,
) : ElevenLabsApi {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(props.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()

    override fun startOutboundCall(
        toNumber: String,
        language: String,
        variables: Map<String, String>,
    ): OutboundCallResult {
        if (!props.configured) throw CallNotStartedException("ElevenLabs not configured")
        val body =
            mapOf(
                "agent_id" to props.agentId,
                "agent_phone_number_id" to props.phoneNumberId,
                "to_number" to toNumber,
                "conversation_initiation_client_data" to
                    mapOf(
                        "dynamic_variables" to variables,
                        "conversation_config_override" to mapOf("agent" to mapOf("language" to language)),
                    ),
            )
        val raw =
            try {
                exchange("POST", "/v1/convai/twilio/outbound-call", json.writeValueAsString(body))
            } catch (e: HttpStatusException) {
                // A rejected request (4xx) was never acted on; after a 5xx the call may or may not have been placed.
                throw if (e.status in 400..499) CallNotStartedException(e.message!!) else e
            } catch (e: ResourceAccessException) {
                // Not even connected: nothing was sent. A timeout after connecting stays unclear.
                val cause = e.cause
                throw if (cause is ConnectException || cause is UnknownHostException || cause is NoRouteToHostException) {
                    CallNotStartedException("ElevenLabs not reachable")
                } else {
                    e
                }
            }
        val response: Map<String, Any?> = json.readValue(raw)
        if (response["success"] == false) {
            throw CallNotStartedException("ElevenLabs refused the call: ${response["message"]}")
        }
        return OutboundCallResult(response["conversation_id"] as String?, response["callSid"] as String?)
    }

    override fun getConversation(conversationId: String): ConversationData {
        check(props.apiKey.isNotBlank()) { "ElevenLabs not configured" }
        return json.readValue(exchange("GET", "/v1/convai/conversations/$conversationId", null))
    }

    private fun exchange(
        method: String,
        path: String,
        body: String?,
    ): String {
        val spec =
            if (method == "POST") {
                client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body!!)
            } else {
                client.get().uri(path)
            }
        return spec
            .header("xi-api-key", props.apiKey)
            .exchange { _, res ->
                val status: HttpStatusCode = res.statusCode
                val text = res.body.readAllBytes().decodeToString()
                if (!status.is2xxSuccessful) {
                    log.warn("ElevenLabs {} {} answered {}", method, path.substringBefore("/conversations/") + "…", status.value())
                    throw HttpStatusException(status.value(), "ElevenLabs $method answered ${status.value()}")
                }
                text
            }!!
    }

    private class HttpStatusException(
        val status: Int,
        message: String,
    ) : IllegalStateException(message)

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
