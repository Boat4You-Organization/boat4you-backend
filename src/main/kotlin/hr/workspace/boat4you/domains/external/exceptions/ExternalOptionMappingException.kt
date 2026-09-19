package hr.workspace.boat4you.domains.external.exceptions

/**
 * The partner CREATED the option but we could not map its response. Deliberately not an [ExternalOptionException]:
 * that one means "the partner said no" (4xx, customer may retry); this one is our fault, so the booking controllers
 * let it fall through to BookingCreationException. The adapter has already released the option when this is thrown.
 */
class ExternalOptionMappingException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)
