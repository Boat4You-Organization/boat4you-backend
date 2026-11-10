package hr.workspace.boat4you.domains.calls

/**
 * The languages agent Nika is configured for on ElevenLabs: the nine languages of the inquiry forms. ElevenLabs refuses an
 * outbound call whose language override the agent does not have, so every code is reduced to one of these before dialling
 * and anything else is answered in English (ruling M16). Keep in step with `infra/elevenlabs/nika-first-messages.json`.
 */
object NikaLanguage {
    const val FALLBACK = "en"
    val SPOKEN: Set<String> = setOf("en", "hr", "de", "es", "fr", "it", "nl", "pl", "pt")

    fun of(code: String?): String {
        val language = code?.trim()?.take(2)?.lowercase()
        return if (language in SPOKEN) language!! else FALLBACK
    }
}
