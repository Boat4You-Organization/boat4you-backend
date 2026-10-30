package hr.workspace.boat4you.domains.calls.enums

/** Why a scheduled call was not dialled (checked right before dialling, design §4). */
enum class SkipReason {
    NO_CONSENT,
    INVALID_PHONE,
    DO_NOT_CALL,
    HUMAN_TAKEOVER,
    OFFER_ALREADY_SENT,
    ARCHIVED,
    RECENT_CONFIRMATION,
    BOOKED,
    NOT_INTERESTED,
    NOT_ON_TEST_LIST,
    STALE,
    MAX_ATTEMPTS,
}
