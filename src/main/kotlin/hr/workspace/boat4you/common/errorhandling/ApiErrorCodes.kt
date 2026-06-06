package hr.workspace.boat4you.common.errorhandling

enum class ApiErrorCodes(
    val code: Int,
    val message: String,
) {
    GENERAL_ERROR(1000, "Unknown error"),
    BAD_CREDENTIALS(1011, "Bad credentials"),
    LOGIN_ATTEMPTS_EXCEEDED(1012, "Maximum number of login attempts exceeded"),
    PASSWORD_RESET_INVALID(1013, "Password reset request invalid"),
    // F1-004: generic message — does not reveal exact minimum (was
    // "could contain at least six characters", which was both an
    // attacker hint AND a misleading typo of "must"). Enum constant
    // name preserved so the frontend error-code switch (1014) keeps
    // working; only the human-readable message changes.
    PASSWORD_INVALID_LENGTH(1014, "Password does not meet the strength requirements"),
    OLD_PASSWORD_INVALID(1015, "Old password not valid"),
    REQUEST_NOT_PARSEABLE(1101, "Request JSON not parseable"),
    INVALID_REQUEST_PARAMETERS(1102, "Invalid request parameters"),
    USER_ALREADY_EXISTS(1201, "User already exists"),
    USER_DOES_NOT_EXIST(1202, "User does not exist"),
    USERS_DO_NOT_EXIST(1203, "Users do not exist"),
    USER_ALREADY_REGISTERED(1210, "User already registered"),
    VERIFICATION_CODE_REQUESTED_TOO_SOON(1211, "Verification code requested too soon"),
    VERIFICATION_CODE_DOES_NOT_MATCH(1212, "Invalid verification code"),
    VERIFICATION_CODE_EXPIRED(1213, "Verification code has expired"),
    VERIFICATION_ATTEMPTS_EXCEEDED(1214, "Too many verification attempts"),
    USERS_INVITE_ALREADY_ACCEPTED(1220, "Users have already accepted invites"),
    USERS_INVITE_INVALID_INVITE_CODE(1221, "Invalid User invite code"),
    USERS_INVITE_EXPIRED(1222, "User invite expired"),
    USERS_INVITE_NOT_ACCEPTED(1223, "User invite not accepted"),
    FIELDS_NOT_MODIFIABLE(1301, "Fields are not modifiable"),
    AGENCY_DOES_NOT_EXIST(1401, "Agency does not exist"),
    YACHT_DOES_NOT_EXIST(1501, "Yacht does not exist"),
    YACHT_NOT_ACTIVE(1502, "Yacht is not active"),
    AGENCY_NOT_ACTIVE(1601, "The yacht's agency is not active"),
    IMAGE_NOT_FOUND(1602, "Image not found"),
    ENTITY_NOT_DELETABLE(1701, "Entity cannot be deleted because there are other entities referencing it"),
    DATA_UNAVAILABLE(2002, "Data unavailable"),
    RESERVATION_FLOW_NOT_EXIST(3001, "Reservation flow does not exist"),
    RESERVATION_NOT_EXIST(3002, "Reservation does not exist"),
    RESERVATION_USER_NOT_EXIST(3003, "Reservation user does not exist"),
    RESERVATION_STATUS_ERROR(3004, "Reservation status error"),
    // B2: booking orchestration failed (partner option / reservation persist)
    // and the flow was compensated (offer freed, flow abandoned). User-facing
    // generic apology; the real cause stays in the backend log.
    BOOKING_CREATION_FAILED(3005, "We're sorry, we couldn't complete your booking. Please try again or contact our support team."),
    RESOURCE_NOT_FOUND(4004, "Requested resource not found"),
    INVOICE_NOT_EXIST(5001, "Invoice does not exist"),
    // 6xxx codes are partner-integration failures (MMK / NauSys). The
    // messages are user-facing — they appear in toast notifications, so
    // they're intentionally generic apologies. Technical details (raw
    // partner response bodies, yachtIds, exception traces) stay in the
    // backend log via ApiErrorHandler's `logger.error`.
    EXTERNAL_SYSTEM_ERROR(6001, "We're sorry, we're experiencing technical difficulties. Please contact our support team."),
    EXTERNAL_OPTION_ERROR(6002, "We're sorry, we're experiencing technical difficulties with this booking. Please contact our support team."),
    EXTERNAL_RESERVATION_ERROR(6003, "We're sorry, we couldn't complete your reservation due to a technical issue. Please contact our support team."),
    EXTERNAL_RESERVATION_CANCELLATION_ERROR(6004, "We're sorry, the cancellation couldn't be processed due to a technical issue. Please contact our support team."),
}
