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
 *  - UNAVAILABLE -> RESERVATION (hard-blocked) — see note below
 *  - OPTION / OPTION_WAITING -> OPTION (visible, inquiry-only)
 *  - everything else (FREE, OPTION_EXPIRED, CANCELLED, INFO, UNKNOWN) -> FREE
 *
 * An expired option (a live partner OPTION row whose optionExpiration has
 * lapsed) is demoted to FREE by the CALLER, which knows whether a live option
 * still backs the offer's period.
 *
 * UNAVAILABLE *does* ride on priced offer rows: the availability overlap-flip
 * (Mmk/NauSysAvailabilitySyncService.updateOffer + V9_11 backfill, 2.6.2026)
 * sets every priced offer that overlaps a RESERVATION/SERVICE to UNAVAILABLE.
 * The matview UNAVAILABLE pre-filter hides those from SEARCH, but the boat-detail
 * standard-offers endpoint reads offer.status live through this mapping, so a
 * default of FREE made the detail calendar show reserved weeks as bookable
 * ("everything available"). UNAVAILABLE therefore must hard-block -> RESERVATION.
 */
fun OfferStatus.toCustomerStatus(): ExternalReservationStatus =
    when (this) {
        OfferStatus.RESERVED -> ExternalReservationStatus.RESERVATION
        OfferStatus.SERVICE -> ExternalReservationStatus.SERVICE
        OfferStatus.UNAVAILABLE -> ExternalReservationStatus.RESERVATION
        OfferStatus.OPTION, OfferStatus.OPTION_WAITING -> ExternalReservationStatus.OPTION
        else -> ExternalReservationStatus.FREE
    }
