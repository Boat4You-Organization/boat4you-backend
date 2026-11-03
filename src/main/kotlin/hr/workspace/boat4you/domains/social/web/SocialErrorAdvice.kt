package hr.workspace.boat4you.domains.social.web

import hr.workspace.boat4you.domains.social.service.SocialConfigException
import hr.workspace.boat4you.domains.social.service.SocialConflictException
import hr.workspace.boat4you.domains.social.service.SocialRateLimitException
import hr.workspace.boat4you.domains.social.service.SocialValidationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Objave errors as `{code, message}` (the admin shows `message`), scoped to the social controllers and ahead of the
 * global ApiErrorHandler, whose catch-all would turn them into a generic 500. Messages are ours or the network's
 * words, never a stack trace.
 */
@RestControllerAdvice(basePackages = ["hr.workspace.boat4you.domains.social.web"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class SocialErrorAdvice {
    @ExceptionHandler(SocialValidationException::class)
    fun validation(e: SocialValidationException) = body(HttpStatus.BAD_REQUEST, "SOCIAL_INVALID", e.message)

    @ExceptionHandler(SocialConflictException::class, ObjectOptimisticLockingFailureException::class)
    fun conflict(e: RuntimeException) = body(HttpStatus.CONFLICT, "SOCIAL_CONFLICT", (e as? SocialConflictException)?.message ?: "The post changed meanwhile; reload")

    @ExceptionHandler(SocialConfigException::class)
    fun config(e: SocialConfigException) = body(HttpStatus.CONFLICT, "SOCIAL_NOT_CONFIGURED", e.message)

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = body(HttpStatus.NOT_FOUND, "SOCIAL_NOT_FOUND", e.message ?: "Not found")

    @ExceptionHandler(SocialUnauthorizedException::class)
    fun unauthorized(e: SocialUnauthorizedException) = body(HttpStatus.UNAUTHORIZED, "SOCIAL_API_KEY", e.message)

    @ExceptionHandler(SocialRateLimitException::class)
    fun tooMany(e: SocialRateLimitException) = body(HttpStatus.TOO_MANY_REQUESTS, "SOCIAL_RATE_LIMIT", e.message)

    private fun body(
        status: HttpStatus,
        code: String,
        message: String?,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("code" to code, "message" to (message ?: status.reasonPhrase)))
}
