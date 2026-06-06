package hr.workspace.boat4you.domains.catalouge.enums

/**
 * Customer-facing collapse of the 10-state partner [OfferStatus] onto the 4
 * honest [ExternalReservationStatus] states the FE gate keys on (Deploy 4
 * status unification). Replaces the old lossy SimpleOfferStatus, which merged
 * RESERVED / SERVICE / OPTION_EXPIRED all into one UNAVAILABLE bucket so the FE
 * could not tell an inquiry-only option from a hard-blocked reservation.
 *
 * Mapping:
 *  - RESERVED -> RESERVATION (hard-blocked)
 *  - SERVICE  -> SERVICE     (hard-blocked, distinct so the FE shows the reason)
 *  - OPTION / OPTION_WAITING -> OPTION (visible, inquiry-only)
 *  - everything else (FREE, OPTION_EXPIRED, CANCELLED, INFO, UNKNOWN) -> FREE
 *
 * An expired option (a live partner OPTION row whose optionExpiration has
 * lapsed) is demoted to FREE by the CALLER, which knows whether a live option
 * still backs the offer's period. UNAVAILABLE (owner-week / regatta) almost
 * never rides on a priced offer row; if QA shows otherwise it gets its own
 * branch, but the matview UNAVAILABLE pre-filter already hides those from
 * search, so the default FREE here is safe.
 */
fun OfferStatus.toCustomerStatus(): ExternalReservationStatus =
    when (this) {
        OfferStatus.RESERVED -> ExternalReservationStatus.RESERVATION
        OfferStatus.SERVICE -> ExternalReservationStatus.SERVICE
        OfferStatus.OPTION, OfferStatus.OPTION_WAITING -> ExternalReservationStatus.OPTION
        else -> ExternalReservationStatus.FREE
    }
