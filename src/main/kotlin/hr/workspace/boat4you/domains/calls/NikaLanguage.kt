package hr.workspace.boat4you.domains.calls

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * The languages agent Nika is configured for on ElevenLabs: the nine languages of the inquiry forms. ElevenLabs refuses an
 * outbound call whose language override the agent does not have, so every code is reduced to one of these before dialling
 * and anything else is answered in English (ruling M16). Keep in step with `infra/elevenlabs/nika-first-messages.json`.
 *
 * Which of them a call starts in is decided by the country of the number Nika dials (Mario 10.11.2026): a French number is
 * greeted in French, a Croatian one in Croatian, a number from a country whose language Nika does not speak (Kuwait,
 * Saudi Arabia, the UK, the USA) in English. The language of the form only decides between the languages of a
 * multilingual country, and stands in when the number tells nothing.
 */
object NikaLanguage {
    const val FALLBACK = "en"
    val SPOKEN: Set<String> = setOf("en", "hr", "de", "es", "fr", "it", "nl", "pl", "pt")

    /** Countries (ISO 3166 alpha-2) where one of Nika's languages is the language of the country. */
    private val BY_COUNTRY: Map<String, String> =
        buildMap {
            put("HR", "hr")
            listOf("DE", "AT", "LI").forEach { put(it, "de") }
            listOf("FR", "MC").forEach { put(it, "fr") }
            listOf("IT", "SM", "VA").forEach { put(it, "it") }
            listOf("NL").forEach { put(it, "nl") }
            listOf("PL").forEach { put(it, "pl") }
            listOf("PT", "BR").forEach { put(it, "pt") }
            listOf("ES", "MX", "AR", "CL", "CO", "PE", "UY", "PY", "BO", "EC", "VE", "CR", "PA", "DO", "GT").forEach { put(it, "es") }
        }

    /** Countries with several of Nika's languages, the most spoken first: the form language picks, else the first. */
    private val MULTILINGUAL: Map<String, List<String>> =
        mapOf(
            "CH" to listOf("de", "fr", "it"),
            "BE" to listOf("nl", "fr", "de"),
            "LU" to listOf("fr", "de"),
        )

    private val util = PhoneNumberUtil.getInstance()

    fun of(code: String?): String {
        val language = code?.trim()?.take(2)?.lowercase()
        return if (language in SPOKEN) language!! else FALLBACK
    }

    /**
     * The language a call to [phoneE164] starts in; [code] is the language of the inquiry form. A number whose country is
     * unknown (missing, unreadable, a satellite or shared-cost code) falls back to [code].
     */
    fun forCall(
        phoneE164: String?,
        code: String?,
    ): String {
        val country = country(phoneE164) ?: return of(code)
        BY_COUNTRY[country]?.let { return it }
        MULTILINGUAL[country]?.let { languages -> return of(code).takeIf { it in languages } ?: languages.first() }
        return FALLBACK
    }

    private fun country(phoneE164: String?): String? =
        try {
            phoneE164?.takeIf { it.isNotBlank() }?.let { util.getRegionCodeForNumber(util.parse(it, "ZZ")) }
        } catch (e: NumberParseException) {
            null
        }
}
