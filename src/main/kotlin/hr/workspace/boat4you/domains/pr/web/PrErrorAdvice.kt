package hr.workspace.boat4you.domains.pr.web

import hr.workspace.boat4you.domains.pr.service.PrException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * PR module errors as `{code, message}` (the admin shows `message`), scoped to the PR controllers and ahead of the
 * global ApiErrorHandler, whose catch-all would turn them into a generic 500. Messages are ours, never a stack trace.
 */
@RestControllerAdvice(basePackages = ["hr.workspace.boat4you.domains.pr.web"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PrErrorAdvice {
    @ExceptionHandler(PrException::class)
    fun pr(e: PrException) = body(e.status, e.code, e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun validation(e: Exception) = body(HttpStatus.BAD_REQUEST, "VALIDATION", "The request is not valid: check the fields and their values")

    /** A unique index won a race the service checks lost (two admins saving the same outlet or contact at once). */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(e: DataIntegrityViolationException) = body(HttpStatus.CONFLICT, "CONFLICT", "It already exists or changed meanwhile; reload")

    private fun body(
        status: HttpStatus,
        code: String,
        message: String?,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("code" to code, "message" to (message ?: status.reasonPhrase)))
}
