package hr.workspace.boat4you.common.errorhandling

import hr.workspace.boat4you.common.exceptions.EntityNotDeletableException
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import hr.workspace.boat4you.common.exceptions.UnmodifiableFieldsException
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
        logger.error("Handling HttpMessageNotReadableException - ${e.message}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.REQUEST_NOT_PARSEABLE.code,
                ApiErrorCodes.REQUEST_NOT_PARSEABLE.message + ": ${e.message}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(e: MethodArgumentNotValidException): ResponseEntity<ErrorSchema> {
        logger.error("Handling MethodArgumentNotValidException - ${e.message}\n${e.stackTraceToString()}")

        val badParameters = e.fieldErrors.associate { it.field to it.defaultMessage }

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
        logger.error("Handling UserAlreadyExistsException - existing properties: ${e.existingProperties}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USER_ALREADY_EXISTS.code,
                ApiErrorCodes.USER_ALREADY_EXISTS.message + " - existing properties: ${e.existingProperties}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UserDoesNotExistException::class)
    fun handleUserDoesNotExistException(e: UserDoesNotExistException): ResponseEntity<ErrorSchema> {
        logger.error("Handling UserDoesNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USER_DOES_NOT_EXIST.code,
                ApiErrorCodes.USER_DOES_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UnmodifiableFieldsException::class)
    fun handleUnmodifiableFieldsException(e: UnmodifiableFieldsException): ResponseEntity<ErrorSchema> {
        logger.error("Handling UnmodifiableFieldsException - unmodifiable fields: ${e.fieldNames}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.FIELDS_NOT_MODIFIABLE.code,
                ApiErrorCodes.FIELDS_NOT_MODIFIABLE.message + ": ${e.fieldNames}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(YachtDoesNotExistException::class)
    fun handleYachtDoesNotExistException(e: YachtDoesNotExistException): ResponseEntity<ErrorSchema> {
        logger.error("Handling YachtDoesNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.YACHT_DOES_NOT_EXIST.code,
                ApiErrorCodes.YACHT_DOES_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(YachtNotActiveException::class)
    fun handleYachtNotActiveException(e: YachtNotActiveException): ResponseEntity<ErrorSchema> {
        logger.error("Handling YachtNotActiveException\n${e.stackTraceToString()}")
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
        logger.error("Handling AgencyNotActiveException\n${e.stackTraceToString()}")
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
        logger.error("Handling AgencyDoesNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.AGENCY_DOES_NOT_EXIST.code,
                ApiErrorCodes.AGENCY_DOES_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ReservationNotExistException::class)
    fun handleReservationNotExistException(e: ReservationNotExistException): ResponseEntity<ErrorSchema> {
        logger.error("Handling ReservationNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_NOT_EXIST.code,
                ApiErrorCodes.RESERVATION_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ImageNotFoundException::class)
    fun handleImageNotFoundException(e: ImageNotFoundException): ResponseEntity<ErrorSchema> {
        // TODO: enable logging if needed
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.IMAGE_NOT_FOUND.code,
                ApiErrorCodes.IMAGE_NOT_FOUND.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ReservationFlowNotExists::class)
    fun handleReservationFlowNotExists(e: ReservationFlowNotExists): ResponseEntity<ErrorSchema> {
        logger.error("Handling ReservationFlowNotExists\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_FLOW_NOT_EXIST.code,
                ApiErrorCodes.RESERVATION_FLOW_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ReservationUserNotExists::class)
    fun handleReservationUserNotExists(e: ReservationUserNotExists): ResponseEntity<ErrorSchema> {
        logger.error("Handling ReservationUserNotExists\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_USER_NOT_EXIST.code,
                e.message!!,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ReservationStatusException::class)
    fun handleReservationStatusException(e: ReservationStatusException): ResponseEntity<ErrorSchema> {
        logger.error("Handling ReservationStatusException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESERVATION_STATUS_ERROR.code,
                "Reservation status must be ${e.requiredStatus}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ParameterValidationException::class)
    fun handleParameterValidationException(e: ParameterValidationException): ResponseEntity<ErrorSchema> {
        logger.error("Handling ParameterValidationException - ${e.badOrMissingParameters}\n${e.stackTraceToString()}")
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
        logger.error("Handling EntityNotDeletableException - referencing entities: ${e.referencingEntities}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.ENTITY_NOT_DELETABLE.code,
                ApiErrorCodes.ENTITY_NOT_DELETABLE.message + ": ${e.referencingEntities}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDataAccessException(e: DataAccessException): ResponseEntity<ErrorSchema> {
        logger.error("Handling DataAccessException - ${e.message}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.DATA_UNAVAILABLE.code,
                ApiErrorCodes.DATA_UNAVAILABLE.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(SQLException::class)
    fun handleSQLException(e: SQLException): ResponseEntity<ErrorSchema> {
        logger.error("Handling SQLException - ${e.message}\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.DATA_UNAVAILABLE.code,
                ApiErrorCodes.DATA_UNAVAILABLE.message + ": ${e.message}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorSchema> {
        logger.error("Handling AccessDeniedException\n${e.stackTraceToString()}")
        return ResponseEntity(
            HttpStatus.FORBIDDEN,
        )
    }

    @ExceptionHandler(InternalLoginException::class)
    fun handleInternalLoginException(e: InternalLoginException): ResponseEntity<ErrorSchema> {
        logger.error("Handling InternalLoginException: ${e.email}: ${e.type.name}")
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
        logger.error("Handling PasswordResetException - ${e.message}\n${e.stackTraceToString()}")
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
        logger.error("Handling UserRegistrationException - ${e.message}\n${e.stackTraceToString()}")
        val errorCode =
            when (e.reason) {
                UserRegistrationException.UserRegistrationExceptionReason.USER_ALREADY_REGISTERED -> ApiErrorCodes.USER_ALREADY_REGISTERED
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_CODE_REQUESTED_TOO_SOON -> ApiErrorCodes.VERIFICATION_CODE_REQUESTED_TOO_SOON
                UserRegistrationException.UserRegistrationExceptionReason.VERIFICATION_CODE_DOES_NOT_MATCH -> ApiErrorCodes.VERIFICATION_CODE_DOES_NOT_MATCH
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
        logger.error("Handling UsersDoNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.USERS_DO_NOT_EXIST.code,
                ApiErrorCodes.USERS_DO_NOT_EXIST.message + " - userIds: ${e.userIds}",
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(UserInviteException::class)
    fun handleUserInviteException(e: UserInviteException): ResponseEntity<ErrorSchema> {
        logger.error("Handling UserInviteException\n${e.stackTraceToString()}")

        val errorCode =
            when (e.type) {
                UserInviteExceptionType.INVITE_ALREADY_ACCEPTED -> ApiErrorCodes.USERS_INVITE_ALREADY_ACCEPTED
                UserInviteExceptionType.INVALID_INVITE_CODE -> ApiErrorCodes.USERS_INVITE_INVALID_INVITE_CODE
                UserInviteExceptionType.INVITE_EXPIRED -> ApiErrorCodes.USERS_INVITE_EXPIRED
            }

        val errorMessage =
            if (e.type == UserInviteExceptionType.INVITE_ALREADY_ACCEPTED) {
                errorCode.message + " - userIds: ${e.userIds}"
            } else {
                errorCode.message
            }

        return ResponseEntity(
            ErrorSchema(
                errorCode.code,
                errorMessage,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(InvoiceNotExistException::class)
    fun handleInvoiceNotExistException(e: InvoiceNotExistException): ResponseEntity<ErrorSchema> {
        logger.error("Handling InvoiceNotExistException\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.INVOICE_NOT_EXIST.code,
                ApiErrorCodes.INVOICE_NOT_EXIST.message,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(ResourceNotFound::class)
    fun handleException(e: ResourceNotFound): ResponseEntity<ErrorSchema> {
        logger.error("Handling ResourceNotFound: ${e.message}\nStack trace:\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.RESOURCE_NOT_FOUND.code,
                ApiErrorCodes.RESOURCE_NOT_FOUND.message,
            ),
            HttpStatus.NOT_EXTENDED,
        )
    }

    @ExceptionHandler(PersistenceException::class)
    fun handleException(e: PersistenceException): ResponseEntity<ErrorSchema> {
        logger.error("Handling general Exception: ${e.message}\nStack trace:\n${e.stackTraceToString()}")
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
        logger.error("Handling ExternalSystemException\n${e.stackTraceToString()}")
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
        logger.error("Handling ExternalOptionException\n${e.stackTraceToString()}")
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
        logger.error("Handling ExternalReservationException\n${e.stackTraceToString()}")
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
        logger.error("Handling ExternalCancellationException\n${e.stackTraceToString()}")
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
        logger.error("Handling general Exception: ${e.message}\nStack trace:\n${e.stackTraceToString()}")
        return ResponseEntity(
            ErrorSchema(
                ApiErrorCodes.GENERAL_ERROR.code,
                ApiErrorCodes.GENERAL_ERROR.message + ": ${e.message}",
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }
}
