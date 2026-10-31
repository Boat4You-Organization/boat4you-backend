package hr.workspace.boat4you.domains.chat.service

/**
 * E-mail and phone in what a visitor typed (31.10.2026: Nihad left his e-mail while a handoff waited, nothing kept it).
 * Conservative on phones: a number without + or 00 starts with 0, has no dots and 9 to 12 digits.
 */
object ContactSniffer {
    /** What the chat stores as an e-mail or a phone (the save_contact rules since P1c). */
    val EMAIL = Regex("""^[^\s@<>]{1,64}@[^\s@<>]{1,190}\.[A-Za-z]{2,24}$""")
    val PHONE = Regex("""^\+?[0-9][0-9 ]{5,19}$""")

    /** ai_chat_session.visitor_email and cc_contact.email are VARCHAR(255): a longer value would fail the whole message. */
    private const val MAX_EMAIL = 254

    // Never starts mid-word or on a dot (müller@ must not become ller@, a stranger's address, review 31.10.2026).
    private val EMAIL_IN_TEXT = Regex("""(?<![\p{L}\p{N}._%+-])[\p{L}\p{N}_%+-][\p{L}\p{N}._%+-]{0,63}@[\p{L}\p{N}.-]{1,190}\.[A-Za-z]{2,24}""")
    private val TITLE_WORD = Regex("""^[A-Z][a-z]+$""")
    private val PHONE_IN_TEXT = Regex("""(?<![\w+])(?:\+|00)?\d[\d ()./-]{5,22}\d(?!\w)""")
    private val SEPARATORS = Regex("""[()./-]""")
    private val SPACES = Regex("""\s+""")

    fun email(text: String): String? = EMAIL_IN_TEXT.findAll(text).map { withoutNextWord(it.value.trimEnd('.')) }.firstOrNull(::isEmail)

    fun isEmail(value: String): Boolean = value.length <= MAX_EMAIL && EMAIL.matches(value)

    /** "ana@gmail.com.Thanks": a capitalised last label after a full domain is the next word, not part of the address. */
    private fun withoutNextWord(address: String): String {
        val domain = address.substringAfter('@')
        val rest = domain.substringBeforeLast('.')
        return if (TITLE_WORD.matches(domain.substringAfterLast('.')) && '.' in rest) address.substringBefore('@') + "@" + rest else address
    }

    fun phone(text: String): String? =
        PHONE_IN_TEXT.findAll(text).firstNotNullOfOrNull { m ->
            val digits = m.value.count(Char::isDigit)
            val international = m.value.startsWith("+") || m.value.startsWith("00")
            // A national number starts with 0 and has no dots: "18.09.-25.09.2027", "15 000 - 20 000" and "2027001234"
            // are dates, budgets and references, never phones (review 31.10.2026).
            val national = m.value.startsWith("0") && '.' !in m.value && digits in 9..12
            if ((international && digits in 8..15) || national) normalised(m.value) else null
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
