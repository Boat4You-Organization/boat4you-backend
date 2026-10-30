package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.web.ElevenLabsSignature
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** ElevenLabs-Signature `t=<unix>,v0=<hex HMAC-SHA256 of "t.body">` (elevenlabs-python webhooks_custom.py, 30.10.2026). */
class ElevenLabsSignatureTest {
    private val secret = "wsec_test"
    private val body = """{"type":"post_call_transcription","data":{"conversation_id":"c1"}}"""
    private val now = Instant.parse("2026-11-03T09:00:00Z")

    private fun sign(
        t: Long,
        b: String = body,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val hex = mac.doFinal("$t.$b".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$t,v0=$hex"
    }

    @Test
    fun `a correct signature passes`() {
        ElevenLabsSignature.verify(sign(now.epochSecond), body, secret, now) shouldBe true
    }

    @Test
    fun `one changed byte of the body fails`() {
        ElevenLabsSignature.verify(sign(now.epochSecond), body.replace("c1", "c2"), secret, now) shouldBe false
    }

    @Test
    fun `a signature older than 30 minutes fails`() {
        ElevenLabsSignature.verify(sign(now.epochSecond - 31 * 60), body, secret, now) shouldBe false
        ElevenLabsSignature.verify(sign(now.epochSecond - 29 * 60), body, secret, now) shouldBe true
    }

    @Test
    fun `a header without v0, a garbled header or an empty secret fails`() {
        ElevenLabsSignature.verify("t=${now.epochSecond}", body, secret, now) shouldBe false
        ElevenLabsSignature.verify("garbage", body, secret, now) shouldBe false
        ElevenLabsSignature.verify(null, body, secret, now) shouldBe false
        ElevenLabsSignature.verify(sign(now.epochSecond), body, "", now) shouldBe false
    }
}
