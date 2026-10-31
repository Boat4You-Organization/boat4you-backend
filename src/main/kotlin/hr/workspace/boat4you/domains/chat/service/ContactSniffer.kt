package hr.workspace.boat4you.domains.chat.service

/**
 * E-mail and phone in what a visitor typed (31.10.2026: Nihad left his e-mail while a handoff waited, nothing kept it).
 * Conservative on phones: a number without + or 00 needs at least 9 digits, so prices, date ranges and references stay out.
 */
object ContactSniffer {
    /** What the chat stores as an e-mail or a phone (the save_contact rules since P1c). */
    val EMAIL = Regex("""^[^\s@<>]{1,64}@[^\s@<>]{1,190}\.[A-Za-z]{2,24}$""")
    val PHONE = Regex("""^\+?[0-9][0-9 ]{5,19}$""")

    private val EMAIL_IN_TEXT = Regex("""[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}""")
    private val PHONE_IN_TEXT = Regex("""(?<![\w+])(?:\+|00)?\d[\d ()./-]{5,22}\d(?!\w)""")
    private val SEPARATORS = Regex("""[()./-]""")
    private val SPACES = Regex("""\s+""")

    fun email(text: String): String? = EMAIL_IN_TEXT.findAll(text).map { it.value.trimEnd('.') }.firstOrNull { EMAIL.matches(it) }

    fun phone(text: String): String? =
        PHONE_IN_TEXT.findAll(text).firstNotNullOfOrNull { m ->
            val digits = m.value.count(Char::isDigit)
            val international = m.value.startsWith("+") || m.value.startsWith("00")
            if (digits !in 8..15 || (!international && digits < 9)) null else normalised(m.value)
        }

    /** A phone typed into the contact form: the visitor meant it as one, so 6 digits are enough. */
    fun formPhone(raw: String): String? = normalised(raw.trim())?.takeIf { it.count(Char::isDigit) >= 6 }

    /** Separators become single spaces and a leading 00 becomes +; null when the result is not a phone. */
    private fun normalised(raw: String): String? {
        val spaced = raw.replace(SEPARATORS, " ").replace(SPACES, " ").trim()
        val plus = if (spaced.startsWith("00")) "+" + spaced.removePrefix("00").trimStart() else spaced
        return plus.takeIf { PHONE.matches(it) }
    }
}
