package hr.workspace.boat4you.domains.catalouge.enums

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import java.util.Locale

enum class LanguageEnum(
    val langName: String,
    val locale: String,
) {
    EN("ENGLISH", "en"),
    FR("FRENCH", "fr"),
    DE("GERMAN", "de"),
    PT("PORTUGUESE", "pt"),
    IT("ITALIAN", "it"),
    ES("SPANISH", "es"),
    HR("CROATIAN", "hr"),
    ;

    companion object {
        fun fromLocale(locale: String): LanguageEnum? {
            return entries.find { it.name.lowercase() == locale.lowercase() }
        }

        fun fromLocale(locale: Locale): LanguageEnum? {
            return entries.find { it.name.lowercase() == locale.language }
        }

        fun getLanguage(
            lang: String?,
            user: UserEntity?,
        ): LanguageEnum {
            val locale =
                if (lang != null && lang != "*") {
                    try {
                        Locale.forLanguageTag(lang)
                    } catch (e: Exception) {
                        Locale.ENGLISH
                    }
                } else {
                    null
                }
            return if (locale != null) {
                fromLocale(locale)
                    ?: throw ParameterValidationException(mapOf("language" to "Language not provided"))
            } else if (user != null) {
                if (user.language != null) {
                    fromLocale(user.language!!.name)
                        ?: throw ParameterValidationException(mapOf("language" to "Language not provided"))
                } else {
                    EN
                }
            } else {
                EN
            }
        }
    }
}
