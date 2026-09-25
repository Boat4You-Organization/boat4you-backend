package hr.workspace.boat4you.domains.review.service

import java.util.Locale

/**
 * Review form links and the languages the form / e-mails exist in. The web runs next-intl with
 * `localePrefix: 'as-needed'` and default `en`: English has no prefix (`/review/{token}`), every other language
 * does (`/de/review/{token}`).
 */
object ReviewLinks {
    /** Languages with an e-mail bundle (messages/email_<lc>.properties) and a web locale. */
    val SUPPORTED_LOCALES: Set<String> = setOf("en", "hr", "de", "fr", "it", "es", "pt", "pl", "nl")

    const val DEFAULT_LOCALE = "en"

    fun normalizeLocale(raw: String?): String? {
        val code = raw?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        return code.takeIf { it in SUPPORTED_LOCALES }
    }

    fun toLocale(code: String): Locale = Locale.forLanguageTag(normalizeLocale(code) ?: DEFAULT_LOCALE)

    fun reviewUrl(
        publicBase: String,
        locale: String,
        token: String,
    ): String {
        val lc = normalizeLocale(locale) ?: DEFAULT_LOCALE
        val prefix = if (lc == DEFAULT_LOCALE) "" else "/$lc"
        return "${publicBase.trimEnd('/')}$prefix/review/$token"
    }
}
