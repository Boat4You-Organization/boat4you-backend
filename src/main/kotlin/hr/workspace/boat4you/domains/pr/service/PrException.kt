package hr.workspace.boat4you.domains.pr.service

import org.springframework.http.HttpStatus

/**
 * Every PR module error the admin should see: PrErrorAdvice answers [status] with `{code, message}`. Codes are stable
 * (the admin may key on them); messages are plain English for a person.
 */
class PrException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message) {
    companion object {
        fun badRequest(
            code: String,
            message: String,
        ) = PrException(HttpStatus.BAD_REQUEST, code, message)

        fun notFound(
            code: String,
            message: String,
        ) = PrException(HttpStatus.NOT_FOUND, code, message)

        fun conflict(
            code: String,
            message: String,
        ) = PrException(HttpStatus.CONFLICT, code, message)
    }
}
