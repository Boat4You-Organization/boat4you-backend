package hr.workspace.boat4you.domains.calls.service

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * The inquiry phone in E.164, or null when it is not written as a valid international number (design §2.6). No default
 * region and no "00" rewriting on purpose: "091 …" or "00…" could be a number in another country, and a call to it would
 * reach someone who never gave consent.
 */
object PhoneNormalizer {
    private val util = PhoneNumberUtil.getInstance()

    fun toE164(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.startsWith("+") } ?: return null
        return try {
            val number = util.parse(value, "ZZ")
            if (util.isValidNumber(number)) util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (e: NumberParseException) {
            null
        }
    }
}
