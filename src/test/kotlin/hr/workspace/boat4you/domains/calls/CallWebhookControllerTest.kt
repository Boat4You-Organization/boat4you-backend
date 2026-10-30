package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsProperties
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.calls.service.CallOutcomeService
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.calls.service.CallWebhookProcessor
import hr.workspace.boat4you.domains.calls.web.CallWebhookController
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** `POST /webhooks/elevenlabs` (design §6.2, §6.6): signed, stored once, processed after the 200. */
class CallWebhookControllerTest : CallsJpaTest() {
    @Autowired private lateinit var events: CallWebhookEventRepository

    private val secret = "wsec_test"
    private val props = ElevenLabsProperties("http://127.0.0.1:1", "k", "a", "p", secret)
    private val clock = Clock.systemUTC()
    private val outcomes by lazy {
        val planner = CallPlanner(calls, inquiries, settings, jdbc, clock)
        CallOutcomeService(calls, inquiries, planner, mock(CallNotifier::class.java), CallAudioStore(Files.createTempDirectory("w").toString()), clock)
    }
    private val processor by lazy { CallWebhookProcessor(events, calls, outcomes, txManager, jacksonObjectMapper()) }
    private val mvc by lazy { MockMvcBuilders.standaloneSetup(CallWebhookController(props, processor, clock)).build() }

    private fun body(
        conversationId: String,
        type: String = "call_initiation_failure",
    ) = """{"type":"$type","event_timestamp":1,"data":{"agent_id":"a","conversation_id":"$conversationId","failure_reason":"busy","metadata":{}}}"""

    private fun sign(b: String): String {
        val t = Instant.now().epochSecond
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "t=$t,v0=" + mac.doFinal("$t.$b".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun post(
        b: String,
        signature: String? = sign(b),
    ) = mvc.post("/webhooks/elevenlabs") {
        contentType = MediaType.APPLICATION_JSON
        content = b
        signature?.let { header("ElevenLabs-Signature", it) }
    }

    @Test
    fun `without a valid signature the answer is 401 and nothing is stored`() {
        post(body("c-1"), signature = null).andExpect { status { isUnauthorized() } }
        post(body("c-1"), signature = "t=1,v0=00").andExpect { status { isUnauthorized() } }
        events.count() shouldBe 0
    }

    @Test
    fun `a signed event is stored and processed`() {
        val c =
            call(inquiry(), status = CallStatus.DIALING) {
                elevenlabsConversationId = "c-2"
                startedAt = Instant.now()
            }
        post(body("c-2")).andExpect { status { isOk() } }
        events.count() shouldBe 1
        events.findAll().single().processedAt.shouldNotBeNull()
        calls.findById(c.id!!).get().status shouldBe CallStatus.BUSY
    }

    @Test
    fun `the same event delivered twice is stored once`() {
        call(inquiry(), status = CallStatus.DIALING) {
            elevenlabsConversationId = "c-3"
            startedAt = Instant.now()
        }
        post(body("c-3")).andExpect { status { isOk() } }
        post(body("c-3")).andExpect { status { isOk() } }
        events.count() shouldBe 1
        calls.findAll().count { it.status == CallStatus.SCHEDULED } shouldBe 1 // one retry, not two
    }

    @Test
    fun `an unknown event type is answered 200 and not stored`() {
        post(body("c-4", type = "something_new")).andExpect { status { isOk() } }
        events.count() shouldBe 0
    }

    @Test
    fun `an event that arrives before its call has the conversation id is processed once the call has it`() {
        post(body("c-5")).andExpect { status { isOk() } }
        val event = events.findAll().single()
        event.processedAt.shouldBeNull()
        val c =
            call(inquiry(), status = CallStatus.DIALING) {
                elevenlabsConversationId = "c-5"
                startedAt = Instant.now()
            }
        processor.process(event.id!!)
        events.findById(event.id!!).get().processedAt.shouldNotBeNull()
        calls.findById(c.id!!).get().status shouldBe CallStatus.BUSY
    }
}
