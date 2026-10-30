package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.calls.elevenlabs.CallNotStartedException
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsClient
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** The outbound-call and conversation calls against a fake ElevenLabs on localhost (no network, no mocks of our code). */
class ElevenLabsClientTest {
    private data class Seen(
        val method: String,
        val path: String,
        val apiKey: String?,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private var status = 200
    private var response = """{"success":true,"message":"ok","conversation_id":"c1","callSid":"CA1"}"""
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("xi-api-key"), ex.requestBody.readAllBytes().decodeToString())
                val bytes = response.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun client(apiKey: String = "k") = ElevenLabsClient(ElevenLabsProperties("http://127.0.0.1:${server.address.port}", apiKey, "agent-1", "phone-1", "whsec"))

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an outbound call sends the agent, the number, the variables and the language`() {
        val result = client().startOutboundCall("+385912345678", "hr", mapOf("site_name" to "Boat4You"))
        result.conversationId shouldBe "c1"
        result.callSid shouldBe "CA1"
        val req = seen.single()
        req.method shouldBe "POST"
        req.path shouldBe "/v1/convai/twilio/outbound-call"
        req.apiKey shouldBe "k"
        val body: Map<String, Any?> = jacksonObjectMapper().readValue(req.body)
        body["agent_id"] shouldBe "agent-1"
        body["agent_phone_number_id"] shouldBe "phone-1"
        body["to_number"] shouldBe "+385912345678"
        @Suppress("UNCHECKED_CAST")
        val init = body["conversation_initiation_client_data"] as Map<String, Any?>
        init["dynamic_variables"] shouldBe mapOf("site_name" to "Boat4You")
        init["conversation_config_override"] shouldBe mapOf("agent" to mapOf("language" to "hr"))
    }

    @Test
    fun `a server error is raised once, never retried`() {
        status = 500
        response = """{"detail":"boom"}"""
        shouldThrow<IllegalStateException> { client().startOutboundCall("+385912345678", "hr", emptyMap()) }
        seen.size shouldBe 1
    }

    @Test
    fun `a refused call (success false) was certainly not placed`() {
        response = """{"success":false,"message":"number not allowed","conversation_id":null,"callSid":null}"""
        shouldThrow<CallNotStartedException> { client().startOutboundCall("+385912345678", "hr", emptyMap()) }
    }

    @Test
    fun `a rejected request (4xx) was certainly not placed`() {
        status = 422
        response = """{"detail":"invalid"}"""
        shouldThrow<CallNotStartedException> { client().startOutboundCall("+385912345678", "hr", emptyMap()) }
    }

    @Test
    fun `after a server error (5xx) it is unclear whether the call was placed`() {
        status = 502
        response = """{"detail":"bad gateway"}"""
        val e = shouldThrow<IllegalStateException> { client().startOutboundCall("+385912345678", "hr", emptyMap()) }
        (e is CallNotStartedException) shouldBe false
    }

    @Test
    fun `an unreachable ElevenLabs was certainly not called`() {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val c = ElevenLabsClient(ElevenLabsProperties("http://127.0.0.1:$closed", "k", "agent-1", "phone-1", "whsec"))
        shouldThrow<CallNotStartedException> { c.startOutboundCall("+385912345678", "hr", emptyMap()) }
    }

    @Test
    fun `without an API key nothing is sent`() {
        shouldThrow<CallNotStartedException> { client(apiKey = "").startOutboundCall("+385912345678", "hr", emptyMap()) }
        seen.size shouldBe 0
    }

    @Test
    fun `a conversation is read with transcript, metadata and analysis`() {
        response =
            """{"conversation_id":"c1","status":"done","transcript":[{"role":"agent","message":"Dobar dan","time_in_call_secs":0,"tool_calls":[]}],
               "metadata":{"call_duration_secs":184,"cost":512,"termination_reason":"end_call tool"},
               "analysis":{"call_successful":"success","transcript_summary":"Klijent potvrdio.",
                 "data_collection_results":{"people":{"value":8}},"evaluation_criteria_results":{"ai_disclosed":{"result":"success","rationale":"ok"}}},
               "unknown_field":1}"""
        val c = client().getConversation("c1")
        seen.single().path shouldBe "/v1/convai/conversations/c1"
        c.status shouldBe "done"
        c.transcript.single().message shouldBe "Dobar dan"
        c.metadata?.callDurationSecs shouldBe 184
        c.metadata?.cost shouldBe 512
        c.analysis?.callSuccessful shouldBe "success"
        c.analysis?.dataCollectionResults?.get("people")?.value shouldBe 8
        c.analysis?.evaluationCriteriaResults?.get("ai_disclosed")?.result shouldBe "success"
    }
}
