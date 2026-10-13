package hr.workspace.boat4you.domains.chat.service

import java.text.Normalizer
import java.util.Locale

/**
 * The visitor's name for the chat (pre-chat step, late /name call, the model's save_contact). Mario 11.10.2026: names
 * like "test" must not pass. Review B2: NFKC, 2-40 letters (plus space, apostrophe, dot, hyphen), no reserved word as
 * the whole name or any part of it. A name that hits a hard [ChatSecurityGuard] rule ("<img src=x onerror=...>",
 * "operator-injected") is [Result.Suspicious]: the session is locked right away instead of asking again.
 */
object NameValidator {
    sealed interface Result {
        /** [name] = the cleaned name to store (NFKC, single spaces, trimmed). */
        data class Valid(
            val name: String,
        ) : Result

        data object Invalid : Result

        data class Suspicious(
            val hit: ChatSecurityGuard.Hit,
        ) : Result
    }

    private val NAME = Regex("""^[\p{L}\p{M}][\p{L}\p{M}' .-]{1,39}$""")
    private val TOKEN_SEPARATORS = Regex("""[ '.-]+""")
    private val INVISIBLE = Regex("""[\x{200B}-\x{200D}\x{2060}-\x{2064}\x{FEFF}\x{00AD}]""")
    private val SPACES = Regex("""\s+""")
    private val TYPOGRAPHIC_APOSTROPHE = Regex("""[\x{2018}\x{2019}\x{02BC}]""")

    private val RESERVED =
        setOf(
            "test", "tester", "testing", "admin", "administrator", "root", "system", "sysadmin", "operator", "developer",
            "dev", "assistant", "bot", "ai", "iris", "claude", "anthropic", "openai", "gpt", "chatgpt", "null", "undefined",
            "none", "user", "guest", "asdf", "qwerty", "boat4you", "support", "staff", "agent",
        )

    fun check(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Invalid
        ChatSecurityGuard.hardHit(raw)?.let { return Result.Suspicious(it) }
        val name =
            INVISIBLE
                .replace(Normalizer.normalize(raw, Normalizer.Form.NFKC), "")
                .replace(TYPOGRAPHIC_APOSTROPHE, "'")
                .replace(SPACES, " ")
                .trim()
        if (!NAME.matches(name)) return Result.Invalid
        val lower = name.lowercase(Locale.ROOT)
        val tokens = lower.split(TOKEN_SEPARATORS).filter { it.isNotEmpty() }
        if (lower.trim('.', '-', '\'', ' ') in RESERVED || tokens.any { it in RESERVED }) return Result.Invalid
        return Result.Valid(name)
    }

    /** The cleaned name, or null when it is not a valid one (save_contact, and the name in the system prompt). */
    fun validOrNull(raw: String?): String? = (check(raw) as? Result.Valid)?.name
}
