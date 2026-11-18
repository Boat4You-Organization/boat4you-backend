package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.calls.service.CallWebhookProcessor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Why a call never started: ElevenLabs' own reason, or the carrier's when ElevenLabs only says "unknown". */
class CallFailureReasonTest {
    private fun reason(json: String) = CallWebhookProcessor.failureReason(ObjectMapper().readTree(json))

    @Test
    fun `busy and no-answer are kept as ElevenLabs sends them`() {
        reason("""{"failure_reason":"busy"}""") shouldBe "busy"
        reason("""{"failure_reason":"no-answer","metadata":{"body":{"CallStatus":"no-answer","SipResponseCode":"480"}}}""") shouldBe "no-answer"
    }

    @Test
    fun `a number Twilio rejects reads as an invalid phone number with the code`() {
        reason("""{"failure_reason":"unknown","metadata":{"body":{"ErrorCode":"13224","ErrorMessage":"invalid phone number "}}}""") shouldBe
            "invalid phone number (Twilio 13224)"
        reason("""{"metadata":{"body":{"ErrorCode":"21211","ErrorMessage":"Invalid 'To' Phone Number"}}}""") shouldBe
            "invalid phone number (Twilio 21211)"
    }

    @Test
    fun `another carrier error shows its message and code, and nothing known stays unknown`() {
        reason("""{"failure_reason":"unknown","metadata":{"body":{"ErrorCode":"13227","ErrorMessage":"No International Authorization"}}}""") shouldBe
            "No International Authorization (Twilio 13227)"
        reason("""{"failure_reason":"unknown"}""") shouldBe "unknown"
        reason("""{}""") shouldBe "unknown"
    }
}
