package hr.workspace.boat4you.domains.calls.service

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * The inquiry phone in E.164, or null when it is not written as a valid international number (design §2.6). No default
 * region and no "00" rewriting on purpose: "091 …" or "00…" could be a number in another country, and a call to it would
 * reach someone who never gave consent. Only mobile and fixed-line numbers: a toll-free, premium-rate or shared-cost number is
 * never a client, and a call to it can cost per minute.
 */
object PhoneNormalizer {
    private val util = PhoneNumberUtil.getInstance()
    private val CALLABLE =
        setOf(
            PhoneNumberUtil.PhoneNumberType.MOBILE,
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE,
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE,
        )

    fun toE164(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.startsWith("+") } ?: return null
        return try {
            val number = util.parse(value, "ZZ")
            if (util.isValidNumber(number) && util.getNumberType(number) in CALLABLE) util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (e: NumberParseException) {
            null
        }
    }
}
