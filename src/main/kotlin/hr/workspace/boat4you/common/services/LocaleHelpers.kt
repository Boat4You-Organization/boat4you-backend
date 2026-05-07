package hr.workspace.boat4you.common.services

import org.openapitools.model.LanguageEnum
import org.springframework.context.i18n.LocaleContextHolder
import java.util.Locale

/**
 * Convert the project's `LanguageEnum` (EN / HR / DE / …) into a Java
 * [Locale]. Used wherever we feed user-language into Spring i18n
 * machinery — `MessageSource.getMessage(..., locale)` and Thymeleaf
 * `Context(locale)` for email rendering.
 *
 * Mapping is 1:1 by ISO-639-1 lowercase code; LanguageEnum.value carries
 * the upper-case form ("HR"), `Locale.forLanguageTag` wants lowercase.
 */
fun LanguageEnum.toLocale(): Locale = Locale.forLanguageTag(this.value.lowercase())

/**
 * Reverse mapping — used when capturing user.language from an inbound
 * request locale (Accept-Language / NEXT_LOCALE cookie negotiated by
 * `next-intl` on the front-end and forwarded as `Accept-Language` by the
 * booking action). Returns null for locales we don't ship UI for (e.g.
 * `ja`, `zh`); caller decides whether to fall back to EN.
 */
fun Locale.toLanguageEnum(): LanguageEnum? {
    val code = this.language.lowercase()
    return LanguageEnum.entries.firstOrNull { it.value.lowercase() == code }
}

/**
 * Resolve the locale to use for transactional email aimed at a specific
 * user. Order:
 *   1. `user.language` if set (captured at first contact — guest checkout
 *      stamps the front-end locale onto the new user record).
 *   2. Current request `LocaleContextHolder` (Spring resolves from
 *      `Accept-Language` header) — only useful when invoked from inside
 *      a request scope.
 *   3. English fallback.
 *
 * Admin-driven flows that should always send English regardless of the
 * recipient's stored language MUST pass `forceEnglish = true` (e.g.
 * admin clicking "Invite user" in the back-office).
 */
fun resolveEmailLocale(
    userLanguage: LanguageEnum?,
    forceEnglish: Boolean = false,
): Locale {
    if (forceEnglish) return Locale.ENGLISH
    userLanguage?.let { return it.toLocale() }
    LocaleContextHolder.getLocale()
        .takeIf { it.language.isNotBlank() && it.language != "und" }
        ?.let { return it }
    return Locale.ENGLISH
}
