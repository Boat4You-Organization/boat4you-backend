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

    /** Another inquiry of the same phone already has its confirmation call; this one is mentioned in that call (design §2.2). */
    SAME_PERSON,

    /** The number may be in time zones too far apart to share a calling window (a Russian mobile): never called automatically. */
    TIMEZONE_UNCLEAR,

    /** Refusal of Mario's "Nazovi sad" while calls are switched off; never stored on a call. */
    CALLS_OFF,
}
