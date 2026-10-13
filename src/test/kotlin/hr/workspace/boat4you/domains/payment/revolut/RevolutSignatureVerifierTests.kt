package hr.workspace.boat4you.domains.payment.revolut

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RevolutSignatureVerifierTests {
    private val nowMs = 1_760_000_000_123L
    private val verifier = RevolutSignatureVerifier(Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC))
    private val body = """{"event":"ORDER_COMPLETED","order_id":"6516e61c-d279-a454-a837-bc52ce55ed49"}""".toByteArray()

    private fun signature(
        ts: Long,
        payload: ByteArray = body,
        secret: String = TEST_WEBHOOK_SECRET,
    ) = "v1=" + revolutSignatureHex(secret, ts.toString(), payload)

    private fun valid(
        ts: String?,
        sig: String?,
        payload: ByteArray = body,
        secret: String = TEST_WEBHOOK_SECRET,
    ) = verifier.isValid(ts, sig, payload, secret)

    @Test
    fun `a single valid v1 signature is accepted`() {
        valid(nowMs.toString(), signature(nowMs)) shouldBe true
    }

    @Test
    fun `several comma-separated signatures - any matching entry is enough`() {
        val other = "v1=" + revolutSignatureHex("wsk_rotated_old_secret", nowMs.toString(), body)
        valid(nowMs.toString(), "$other,${signature(nowMs)}") shouldBe true
        valid(nowMs.toString(), "${signature(nowMs)}, $other") shouldBe true
        valid(nowMs.toString(), "$other,$other") shouldBe false
    }

    @Test
    fun `one tampered byte in the body or in the signature is rejected`() {
        val tampered = body.copyOf().also { it[10] = (it[10] + 1).toByte() }
        valid(nowMs.toString(), signature(nowMs), payload = tampered) shouldBe false

        val sig = signature(nowMs)
        val flipped = sig.dropLast(1) + (if (sig.last() == '0') '1' else '0')
        valid(nowMs.toString(), flipped) shouldBe false
    }

    @Test
    fun `a signature made with another secret is rejected`() {
        valid(nowMs.toString(), signature(nowMs, secret = "wsk_someone_else")) shouldBe false
    }

    @Test
    fun `timestamp window is plus or minus 300000 ms inclusive`() {
        for (ts in listOf(nowMs - 300_000, nowMs + 300_000)) {
            valid(ts.toString(), signature(ts)) shouldBe true
        }
        for (ts in listOf(nowMs - 300_001, nowMs + 300_001)) {
            valid(ts.toString(), signature(ts)) shouldBe false
        }
    }

    @Test
    fun `a seconds-valued timestamp is rejected even when correctly signed`() {
        val seconds = nowMs / 1000
        valid(seconds.toString(), signature(seconds)) shouldBe false
    }

    @Test
    fun `missing or malformed headers and a blank secret are invalid`() {
        val ts = nowMs.toString()
        val hex = signature(nowMs).removePrefix("v1=")
        valid(null, signature(nowMs)) shouldBe false
        valid("", signature(nowMs)) shouldBe false
        valid("abc", signature(nowMs)) shouldBe false
        valid("-$ts", signature(nowMs)) shouldBe false
        valid("$ts.0", signature(nowMs)) shouldBe false
        valid(ts, null) shouldBe false
        valid(ts, "") shouldBe false
        valid(ts, hex) shouldBe false
        valid(ts, "v0=$hex") shouldBe false
        valid(ts, "v1=") shouldBe false
        valid(ts, "v1=${hex.dropLast(2)}") shouldBe false
        valid(ts, "v1=${hex.dropLast(1)}z") shouldBe false
        valid(ts, signature(nowMs), secret = "") shouldBe false
        valid(ts, signature(nowMs, secret = " "), secret = " ") shouldBe false
    }

    @Test
    fun `header values are trimmed and uppercase hex is accepted`() {
        val sig = signature(nowMs)
        valid(" $nowMs ", sig) shouldBe true
        valid(nowMs.toString(), "v1=" + sig.removePrefix("v1=").uppercase()) shouldBe true
    }
}
