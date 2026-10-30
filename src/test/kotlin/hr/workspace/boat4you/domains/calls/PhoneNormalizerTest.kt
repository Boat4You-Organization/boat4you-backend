package hr.workspace.boat4you.domains.calls

import com.google.i18n.phonenumbers.PhoneNumberUtil
import hr.workspace.boat4you.domains.calls.service.PhoneNormalizer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Only a number written in international form is ever called (design §2.6): a local "091…" could be someone else's. */
class PhoneNormalizerTest {
    @Test
    fun `international numbers with spaces or punctuation become E164`() {
        PhoneNormalizer.toE164("+385 91 234 5678") shouldBe "+385912345678"
        PhoneNormalizer.toE164("+1 (212) 555-0100") shouldBe "+12125550100"
    }

    @Test
    fun `a number without the plus sign or an invalid one is never callable`() {
        PhoneNormalizer.toE164("091 234 5678").shouldBeNull()
        PhoneNormalizer.toE164("00385912345678").shouldBeNull()
        PhoneNormalizer.toE164("+385 1").shouldBeNull()
        PhoneNormalizer.toE164(null).shouldBeNull()
        PhoneNormalizer.toE164("   ").shouldBeNull()
    }

    @Test
    fun `toll-free, premium-rate and shared-cost numbers are never callable`() {
        val util = PhoneNumberUtil.getInstance()

        fun example(
            region: String,
            type: PhoneNumberUtil.PhoneNumberType,
        ) = util.format(util.getExampleNumberForType(region, type), PhoneNumberUtil.PhoneNumberFormat.E164)
        PhoneNormalizer.toE164(example("US", PhoneNumberUtil.PhoneNumberType.TOLL_FREE)).shouldBeNull()
        PhoneNormalizer.toE164(example("GB", PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE)).shouldBeNull()
        PhoneNormalizer.toE164(example("ES", PhoneNumberUtil.PhoneNumberType.SHARED_COST)).shouldBeNull()
        PhoneNormalizer.toE164(example("HR", PhoneNumberUtil.PhoneNumberType.MOBILE)) shouldBe example("HR", PhoneNumberUtil.PhoneNumberType.MOBILE)
        PhoneNormalizer.toE164(example("DE", PhoneNumberUtil.PhoneNumberType.FIXED_LINE)) shouldBe example("DE", PhoneNumberUtil.PhoneNumberType.FIXED_LINE)
    }
}
