package hr.workspace.boat4you.domains.catalouge.capacity

/**
 * Text rules shared by the partner capacity sync, the note parser and the sanitizer (capacity contract v1, 6.10.2026).
 * They are the same as the frontends' yachtCapacity.ts `normalizeNote` / `isShortNote`, so the backend and the seven
 * sites agree on what a note is.
 */
object CapacityText {
    /** Longest note a search card may carry. */
    const val SHORT_NOTE_MAX = 12

    /** Longest note / label any public surface shows; the sanitizer hides longer ones. */
    const val NOTE_MAX = 120

    /** JavaScript's `\s` (what the frontends collapse), not Java's ASCII-only one. */
    private val WHITESPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
    private val LANGUAGE_NEUTRAL = Regex("^[0-9+/() ]+$")
    private val DIGIT = Regex("[0-9]")

    /** Trim, no-break spaces to a space, every blank run to one space; blank -> null. Nothing else changes. */
    fun normalizeNote(raw: String?): String? {
        if (raw == null) return null
        val clean = raw.replace(WHITESPACE, " ").trim(' ')
        return clean.ifEmpty { null }
    }

    /** Digits, `+ / ( )` and blanks only, with at least one digit: reads the same in every language. */
    fun isLanguageNeutral(note: String): Boolean = LANGUAGE_NEUTRAL.matches(note) && DIGIT.containsMatchIn(note)

    /** The note as a search card may carry it: language-neutral and at most [SHORT_NOTE_MAX] characters. */
    fun isShortNote(note: String): Boolean = note.length <= SHORT_NOTE_MAX && isLanguageNeutral(note)
}
