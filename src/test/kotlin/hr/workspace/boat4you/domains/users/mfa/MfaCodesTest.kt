package hr.workspace.boat4you.domains.users.mfa

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** TOTP as Google Authenticator computes it: the RFC 6238 test vectors (SHA-1 secret "12345678901234567890"). */
class MfaCodesTest {
    private val rfcSecret = MfaCodes.base32("12345678901234567890".toByteArray())

    @Test
    fun `RFC 6238 vectors, last 6 digits`() {
        rfcSecret shouldBe "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        MfaCodes.totpAt(rfcSecret, 59L / 30) shouldBe "287082"
        MfaCodes.totpAt(rfcSecret, 1111111109L / 30) shouldBe "081804"
        MfaCodes.totpAt(rfcSecret, 1234567890L / 30) shouldBe "005924"
        MfaCodes.totpAt(rfcSecret, 2000000000L / 30) shouldBe "279037"
    }

    @Test
    fun `a code is accepted one step either side, never twice, never malformed`() {
        val now = 1234567890L
        val step = now / 30
        MfaCodes.matchTotp(rfcSecret, "005924", null, now) shouldBe step
        MfaCodes.matchTotp(rfcSecret, "005 924", null, now) shouldBe step
        MfaCodes.matchTotp(rfcSecret, MfaCodes.totpAt(rfcSecret, step - 1), null, now) shouldBe step - 1
        MfaCodes.matchTotp(rfcSecret, MfaCodes.totpAt(rfcSecret, step + 2), null, now) shouldBe null
        MfaCodes.matchTotp(rfcSecret, "005924", step, now) shouldBe null
        MfaCodes.matchTotp(rfcSecret, "12345", null, now) shouldBe null
    }

    @Test
    fun `base32 round trip and recovery codes`() {
        val bytes = MfaCodes.randomBytes(20)
        MfaCodes.fromBase32(MfaCodes.base32(bytes)).toList() shouldBe bytes.toList()
        val code = MfaCodes.newRecoveryCode()
        code.length shouldBe 11
        MfaCodes.normaliseRecoveryCode(" ${code.uppercase()} ") shouldBe code.replace("-", "")
        MfaCodes.newRecoveryCode() shouldNotBe code
        MfaCodes.otpauthUri("ABC", "ana@example.com") shouldBe
            "otpauth://totp/Boat4You%20Admin:ana%40example.com?secret=ABC&issuer=Boat4You%20Admin&algorithm=SHA1&digits=6&period=30"
    }
}
