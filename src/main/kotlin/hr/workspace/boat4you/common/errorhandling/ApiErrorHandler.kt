package hr.workspace.boat4you.common.errorhandling

import hr.workspace.boat4you.common.exceptions.EntityNotDeletableException
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import hr.workspace.boat4you.common.exceptions.UnmodifiableFieldsException
import hr.workspace.boat4you.common.services.LogMasking
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.external.exceptions.ExternalCancellationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException
import hr.workspace.boat4you.domains.external.exceptions.ExternalReservationException
import hr.workspace.boat4you.domains.external.exceptions.ExternalSystemException
import hr.workspace.boat4you.domains.invoice.exceptions.InvoiceNotExistException
import hr.workspace.boat4you.domains.reservation.exceptions.BookingCreationException
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationFlowNotExists
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationStatusException
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationUserNotExists
import hr.workspace.boat4you.domains.users.exceptions.UserAlreadyExistsException
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.exceptions.UserInviteException
import hr.workspace.boat4you.domains.users.exceptions.UserInviteExceptionType
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException
import hr.workspace.boat4you.domains.users.exceptions.UsersDoNotExistException
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.exceptions.PasswordException
import jakarta.persistence.PersistenceException
import org.openapitools.model.ErrorSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
// F5-001: explicit import for Spring Security's AccessDeniedException.
// Without this, the bare `AccessDeniedException` reference below resolves
// via Kotlin's default `kotlin.io.*` import to `kotlin.io.AccessDeniedException`
// (a file-I/O exception), which means Spring Security 403s were not
// caught here and fell through to the catch-all 500 handler — a silent
// production bug where authenticated-but-unauthorized requests looked
// like server crashes in the logs and to the customer.
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.sql.SQLException

@ControllerAdvice
@Suppress("TooManyFunctions")
internal class ApiErrorHandler {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ErrorSchema> {
        // F5-002: drop `e.message` from the customer-bound body. Jackson
        // parse errors include the JSON path of the offending node and
        // sometimes raw input — F1-055 leak vector.
        logger.warn("HttpMessageNotReadableException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.REQUEST_NOT_PARSEABLE.code,
                ApiErrorCodes.REQUEST_NOT_PARSEABLE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(e: MethodArgumentNotValidException): ResponseEntity<ErrorSchema> {
        val badParameters = e.fieldErrors.associate { it.field to it.defaultMessage }
        // F5-007: bean-validation failures are routine 400s, not server errors.
        // Bean-validation messages (field + default) are intentional API
        // contract — the customer's frontend renders them inline — so they
        // continue to appear in the response body. Internal `e.message`
        // (which can include framework stack info) stays in the log only.
        logger.warn("MethodArgumentNotValidException — bad params: {}", badParameters)

        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.INVALID_REQUEST_PARAMETERS.code,
                ApiErrorCodes.INVALID_REQUEST_PARAMETERS.message + ": $badParameters",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun handleUserAlreadyExistsException(e: UserAlreadyExistsException): ResponseEntity<ErrorSchema> {
        // F5-003: do not echo `existingProperties` to the customer. The
        // response previously said which user property already existed
        // (email vs phone vs ...), which trivially confirmed whether an
        // email is registered in the system (account-enumeration channel
        // feeding F1-068). The structured detail stays in the log so admin
        // workflows (admin-side user create) still get the diagnostic.
        logger.warn("UserAlreadyExistsException — existing properties: {}", e.existingProperties)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USER_ALREADY_EXISTS.code,
                ApiErrorCodes.USER_ALREADY_EXISTS.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UserDoesNotExistException::class)
    fun handleUserDoesNotExistException(e: UserDoesNotExistException): ResponseEntity<ErrorSchema> {
        // F5-004: not-found → 404. F5-007: routine 4xx → warn, no full stack.
        logger.warn("UserDoesNotExistException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USER_DOES_NOT_EXIST.code,
                ApiErrorCodes.USER_DOES_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(UnmodifiableFieldsException::class)
    fun handleUnmodifiableFieldsException(e: UnmodifiableFieldsException): ResponseEntity<ErrorSchema> {
        // F5-003: internal entity-field names stay in the log; customer
        // gets the generic message.
        logger.warn("UnmodifiableFieldsException — fields: {}", e.fieldNames)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.FIELDS_NOT_MODIFIABLE.code,
                ApiErrorCodes.FIELDS_NOT_MODIFIABLE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(YachtDoesNotExistException::class)
    fun handleYachtDoesNotExistException(e: YachtDoesNotExistException): ResponseEntity<ErrorSchema> {
        logger.warn("YachtDoesNotExistException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.YACHT_DOES_NOT_EXIST.code,
                ApiErrorCodes.YACHT_DOES_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(YachtNotActiveException::class)
    fun handleYachtNotActiveException(e: YachtNotActiveException): ResponseEntity<ErrorSchema> {
        logger.warn("YachtNotActiveException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.YACHT_NOT_ACTIVE.code,
                ApiErrorCodes.YACHT_NOT_ACTIVE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(AgencyNotActiveException::class)
    fun handleAgencyNotActiveException(e: AgencyNotActiveException): ResponseEntity<ErrorSchema> {
        logger.warn("AgencyNotActiveException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.AGENCY_NOT_ACTIVE.code,
                ApiErrorCodes.AGENCY_NOT_ACTIVE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(AgencyDoesNotExistException::class)
    fun handleAgencyDoesNotExistException(e: AgencyDoesNotExistException): ResponseEntity<ErrorSchema> {
        logger.warn("AgencyDoesNotExistException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.AGENCY_DOES_NOT_EXIST.code,
                ApiErrorCodes.AGENCY_DOES_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ReservationNotExistException::class)
    fun handleReservationNotExistException(e: ReservationNotExistException): ResponseEntity<ErrorSchema> {
        logger.warn("ReservationNotExistException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_NOT_EXIST.code,
                ApiErrorCodes.RESERVATION_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ImageNotFoundException::class)
    fun handleImageNotFoundException(e: ImageNotFoundException): ResponseEntity<ErrorSchema> {
        // F5-010: previously silent (TODO comment) — combined with F4-010
        // (FileSystemService path concatenation) and F1-021 (path traversal
        // canonicalization), zero audit trail on path probing.
        // F5-004: not-found → 404.
        logger.warn("ImageNotFoundException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.IMAGE_NOT_FOUND.code,
                ApiErrorCodes.IMAGE_NOT_FOUND.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ReservationFlowNotExists::class)
    fun handleReservationFlowNotExists(e: ReservationFlowNotExists): ResponseEntity<ErrorSchema> {
        logger.warn("ReservationFlowNotExists")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_FLOW_NOT_EXIST.code,
                ApiErrorCodes.RESERVATION_FLOW_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ReservationUserNotExists::class)
    fun handleReservationUserNotExists(e: ReservationUserNotExists): ResponseEntity<ErrorSchema> {
        // F5-002: previously echoed `e.message!!` directly to customer.
        // Use the canonical generic message instead — the specific reason
        // (e.g. reservationFlowId lookup miss) stays in the log.
        // F5-004: not-found → 404.
        logger.warn("ReservationUserNotExists: {}", e.message)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_USER_NOT_EXIST.code,
                ApiErrorCodes.RESERVATION_USER_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ReservationStatusException::class)
    fun handleReservationStatusException(e: ReservationStatusException): ResponseEntity<ErrorSchema> {
        logger.warn("ReservationStatusException — requiredStatus={}", e.requiredStatus)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_STATUS_ERROR.code,
                "Reservation status must be ${e.requiredStatus}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(BookingCreationException::class)
    fun handleBookingCreationException(e: BookingCreationException): ResponseEntity<ErrorSchema> {
        // B2: orchestration failed and was already compensated (offer freed,
        // flow abandoned) before this was thrown. The raw cause (NPE / illegal
        // state / partner glitch) stays in the log; the customer gets a clear
        // generic apology with a real body instead of the catch-all 500 that
        // would otherwise leak internal detail or look like a crash.
        logger.error("BookingCreationException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.BOOKING_CREATION_FAILED.code,
                ApiErrorCodes.BOOKING_CREATION_FAILED.message,
            ),
            HttpStatus.BAD_GATEWAY,
        )
    }

    @ExceptionHandler(ParameterValidationException::class)
    fun handleParameterValidationException(e: ParameterValidationException): ResponseEntity<ErrorSchema> {
        // Validation messages on this exception are crafted at the call
        // site (developer-authored "Provide either X or Y" strings) — they
        // are intentionally part of the API contract, so they stay in the
        // body. F5-007 just downgrades the log level.
        logger.warn("ParameterValidationException — params: {}", e.badOrMissingParameters)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.INVALID_REQUEST_PARAMETERS.code,
                ApiErrorCodes.INVALID_REQUEST_PARAMETERS.message + ": ${e.badOrMissingParameters}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(EntityNotDeletableException::class)
    fun handleEntityNotDeletableException(e: EntityNotDeletableException): ResponseEntity<ErrorSchema> {
        // F5-003: referencing-entities map names internal table/relationship
        // structure; stays in the log only.
        logger.warn("EntityNotDeletableException — referencing entities: {}", e.referencingEntities)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.ENTITY_NOT_DELETABLE.code,
                ApiErrorCodes.ENTITY_NOT_DELETABLE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDataAccessException(e: DataAccessException): ResponseEntity<ErrorSchema> {
        // F5-004: a DataAccessException is server-side (connection lost,
        // deadlock retry exhausted, etc.) — 500, not 400.
        logger.error("DataAccessException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.DATA_UNAVAILABLE.code,
                ApiErrorCodes.DATA_UNAVAILABLE.message,
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }

    @ExceptionHandler(SQLException::class)
    fun handleSQLException(e: SQLException): ResponseEntity<ErrorSchema> {
        // F5-002: do not echo the JDBC driver's message (column names,
        // constraint names, sometimes literal values) to the customer.
        // F5-004: SQLException is a server-side failure, not a client
        // mistake — 500, not 400.
        logger.error("SQLException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.DATA_UNAVAILABLE.code,
                ApiErrorCodes.DATA_UNAVAILABLE.message,
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorSchema> {
        // F5-007: expected 403 from Spring Security's @PreAuthorize/@PostAuthorize
        // is a routine outcome, not an application error — log at WARN with the
        // exception attached so the operator can still trace it if needed,
        // without polluting ERROR-level dashboards.
        // F5-009: return a real body instead of an empty 403 so the frontend
        // can render a generic "permission denied" toast like every other
        // handler in this class.
        logger.warn("Access denied: {}", e.message)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.GENERAL_ERROR.code,
                ApiErrorCodes.GENERAL_ERROR.message,
            ),
            HttpStatus.FORBIDDEN,
        )
    }

    @ExceptionHandler(InternalLoginException::class)
    fun handleInternalLoginException(e: InternalLoginException): ResponseEntity<ErrorSchema> {
        // F5-007: failed login is a routine 4xx, not an application
        // error — WARN level.
        // F5-006: mask the email before it lands in operator dashboards
        // / ELK. The masked form still gives ops "same person retrying
        // 5× this minute" correlation within a single log session;
        // raw email belongs in the DB audit trail, not in flight logs.
        logger.warn("InternalLoginException: {}: {}", LogMasking.maskEmail(e.email), e.type.name)
        val errorCode =
            when (e.type) {
                InternalLoginException.Type.BAD_CREDENTIALS -> ApiErrorCodes.BAD_CREDENTIALS
                InternalLoginException.Type.USER_INVITE_NOT_ACCEPTED -> ApiErrorCodes.USERS_INVITE_NOT_ACCEPTED
                else -> ApiErrorCodes.LOGIN_ATTEMPTS_EXCEEDED
            }

        return ResponseEntity(
            ErrorSchema(
                errorCode.code,
                errorCode.message,
            ),
            HttpStatus.FORBIDDEN,
        )
    }

    @ExceptionHandler(PasswordException::class)
    fun handlePasswordResetException(e: PasswordException): ResponseEntity<ErrorSchema> {
        logger.warn("PasswordException type={}", e.type)
        val errorCode =
            when (e.type) {
                PasswordException.PasswordExceptionType.PASSWORD_RESET_INVALID -> ApiErrorCodes.PASSWORD_RESET_INVALID
                PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH -> ApiErrorCodes.PASSWORD_INVALID_LENGTH
                PasswordException.PasswordExceptionType.OLD_PASSWORD_INVALID -> ApiErrorCodes.OLD_PASSWORD_INVALID
            }

        return ResponseEntity(
            ErrorSchema(
                errorCode.code,
                errorCode.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UserRegistrationException::class)
    fun handleUserRegistrationException(e: UserRegistrationException): ResponseEntity<ErrorSchema> {
        logger.warn("UserRegistrationException reason={}", e.reason)
        val errorCode =
            when (e.reason) {
                UserRegistrationException.UserRegistrationExceptionReason.USER_ALREADY_REGISTERED -> ApiErrorCodes.USER_ALREADY_REGISTERED
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_CODE_REQUESTED_TOO_SOON -> ApiErrorCodes.VERIFICATION_CODE_REQUESTED_TOO_SOON
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_CODE_DOES_NOT_MATCH -> ApiErrorCodes.VERIFICATION_CODE_DOES_NOT_MATCH
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_CODE_EXPIRED -> ApiErrorCodes.VERIFICATION_CODE_EXPIRED
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_ATTEMPTS_EXCEEDED -> ApiErrorCodes.VERIFICATION_ATTEMPTS_EXCEEDED
            }

        return ResponseEntity(
            ErrorSchema(
                errorCode.code,
                errorCode.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UsersDoNotExistException::class)
    fun handleUsersDoNotExistException(e: UsersDoNotExistException): ResponseEntity<ErrorSchema> {
        // F5-003: leaking the set of unknown userIds back as part of the
        // customer body is an enumeration channel.
        // F5-004: not-found → 404.
        logger.warn("UsersDoNotExistException — userIds: {}", e.userIds)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USERS_DO_NOT_EXIST.code,
                ApiErrorCodes.USERS_DO_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(UserInviteException::class)
    fun handleUserInviteException(e: UserInviteException): ResponseEntity<ErrorSchema> {
        // F5-003: previously the INVITE_ALREADY_ACCEPTED branch echoed
        // userIds. Same enumeration concern as UsersDoNotExist — keep in
        // log only.
        logger.warn("UserInviteException type={} userIds={}", e.type, e.userIds)

        val errorCode =
            when (e.type) {
                UserInviteExceptionType.INVITE_ALREADY_ACCEPTED -> ApiErrorCodes.USERS_INVITE_ALREADY_ACCEPTED
                UserInviteExceptionType.INVALID_INVITE_CODE -> ApiErrorCodes.USERS_INVITE_INVALID_INVITE_CODE
                UserInviteExceptionType.INVITE_EXPIRED -> ApiErrorCodes.USERS_INVITE_EXPIRED
            }

        return ResponseEntity(
            ErrorSchema(
                errorCode.code,
                errorCode.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(InvoiceNotExistException::class)
    fun handleInvoiceNotExistException(e: InvoiceNotExistException): ResponseEntity<ErrorSchema> {
        logger.warn("InvoiceNotExistException")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.INVOICE_NOT_EXIST.code,
                ApiErrorCodes.INVOICE_NOT_EXIST.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(ResourceNotFound::class)
    fun handleException(e: ResourceNotFound): ResponseEntity<ErrorSchema> {
        // F5-004: previously returned 510 NOT_EXTENDED — a confusing
        // and incorrect status. Resource-not-found is 404.
        logger.warn("ResourceNotFound: {}", e.message)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESOURCE_NOT_FOUND.code,
                ApiErrorCodes.RESOURCE_NOT_FOUND.message,
            ),
            HttpStatus.NOT_FOUND,
        )
    }

    @ExceptionHandler(PersistenceException::class)
    fun handleException(e: PersistenceException): ResponseEntity<ErrorSchema> {
        // F5-002: don't echo the persistence-provider message to customer.
        logger.error("PersistenceException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.GENERAL_ERROR.code,
                ApiErrorCodes.GENERAL_ERROR.message,
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }

    @ExceptionHandler(ExternalSystemException::class)
    fun handleExternalSystemException(e: ExternalSystemException): ResponseEntity<ErrorSchema> {
        // Partner integration failure — log at error with exception so the
        // partner-side trace is visible, but the customer-facing message
        // (set in ApiErrorCodes line 44) is the generic apology by design.
        logger.error("ExternalSystemException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.EXTERNAL_SYSTEM_ERROR.code,
                ApiErrorCodes.EXTERNAL_SYSTEM_ERROR.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ExternalOptionException::class)
    fun handleExternalOptionException(e: ExternalOptionException): ResponseEntity<ErrorSchema> {
        // Always log the partner-supplied reason (yacht not available, illegal
        // access, etc.) for backend debugging. The customer toast intentionally
        // shows only the generic apology defined on the enum — Mario's call.
        logger.error("ExternalOptionException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.EXTERNAL_OPTION_ERROR.code,
                ApiErrorCodes.EXTERNAL_OPTION_ERROR.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ExternalReservationException::class)
    fun handleExternalReservationException(e: ExternalReservationException): ResponseEntity<ErrorSchema> {
        logger.error("ExternalReservationException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.EXTERNAL_RESERVATION_ERROR.code,
                ApiErrorCodes.EXTERNAL_RESERVATION_ERROR.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ExternalCancellationException::class)
    fun handleExternalCancellationException(e: ExternalCancellationException): ResponseEntity<ErrorSchema> {
        logger.error("ExternalCancellationException", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.EXTERNAL_RESERVATION_CANCELLATION_ERROR.code,
                ApiErrorCodes.EXTERNAL_RESERVATION_CANCELLATION_ERROR.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorSchema> {
        // F5-002: never echo a raw catch-all `e.message` to the customer.
        // It is the largest single leak surface in the file — any unhandled
        // server-side failure (NPE with field name, third-party library
        // assertion text, internal stack frame info) would otherwise land
        // in the toast.
        logger.error("Unhandled exception", e)
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.GENERAL_ERROR.code,
                ApiErrorCodes.GENERAL_ERROR.message,
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }
}
