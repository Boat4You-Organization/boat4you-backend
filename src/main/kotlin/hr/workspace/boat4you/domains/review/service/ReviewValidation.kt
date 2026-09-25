package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.dto.ReviewValuesDto

/**
 * Validates and normalises a submitted review. Every problem is collected and reported at once as a 400
 * (ParameterValidationException -> INVALID_REQUEST_PARAMETERS with a field -> reason map the form can show).
 */
object ReviewValidation {
    const val MAX_TEXT_LENGTH = 3000
    const val MAX_TITLE_LENGTH = 120
    private const val MIN_SCORE = 1
    private const val MAX_SCORE = 5

    // Control characters except TAB / LF (CR is normalised away first).
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    fun validate(
        kind: ReviewKind,
        request: ReviewSubmitRequest,
        fallbackLocale: String,
    ): ReviewValuesDto {
        val errors = linkedMapOf<String, String>()

        val rating = request.rating
        if (rating == null) {
            errors["rating"] = "required"
        } else if (rating !in MIN_SCORE..MAX_SCORE) {
            errors["rating"] = "must be between 1 and 5"
        }

        val scores = linkedMapOf<String, Int>()
        request.scores.orEmpty().forEach { (key, value) ->
            when {
                key !in kind.scoreKeys -> errors["scores.$key"] = "not a ${kind.name} score"
                value == null -> Unit
                value !in MIN_SCORE..MAX_SCORE -> errors["scores.$key"] = "must be between 1 and 5"
                else -> scores[key] = value
            }
        }

        val title = clean(request.title, singleLine = true)
        if (title != null && title.codePointCount(0, title.length) > MAX_TITLE_LENGTH) {
            errors["title"] = "must be at most $MAX_TITLE_LENGTH characters"
        }

        val text = clean(request.text, singleLine = false)
        if (text != null && text.codePointCount(0, text.length) > MAX_TEXT_LENGTH) {
            errors["text"] = "must be at most $MAX_TEXT_LENGTH characters"
        }

        val locale =
            if (request.locale.isNullOrBlank()) {
                ReviewLinks.normalizeLocale(fallbackLocale) ?: ReviewLinks.DEFAULT_LOCALE
            } else {
                ReviewLinks.normalizeLocale(request.locale).also {
                    if (it == null) errors["locale"] = "must be one of ${ReviewLinks.SUPPORTED_LOCALES.sorted()}"
                }
            }

        if (errors.isNotEmpty()) throw ParameterValidationException(errors)

        return ReviewValuesDto(
            rating = rating!!,
            scores = scores.toSortedMap(compareBy { kind.scoreKeys.indexOf(it) }),
            title = title,
            text = text,
            publishConsent = request.publishConsent ?: false,
            locale = locale!!,
        )
    }

    /** Trim, CRLF -> LF, drop control characters; a title is one line. Blank -> null. */
    internal fun clean(
        raw: String?,
        singleLine: Boolean,
    ): String? {
        if (raw == null) return null
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (singleLine) s = s.replace('\n', ' ').replace('\t', ' ')
        s = CONTROL_CHARS.replace(s, "").trim()
        return s.ifBlank { null }
    }
}
