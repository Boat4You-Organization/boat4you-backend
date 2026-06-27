package hr.workspace.boat4you.domains.catalouge.enums

import org.openapitools.client.nausys.model.RestYachtReservationOccupancy
import java.time.LocalDateTime

enum class ExternalReservationStatus(
    val value: Int,
) {
    UNKNOWN(0),
    OPTION(1),
    RESERVATION(2),
    SERVICE(3),
    FREE(4),
    ;

    /**
     * option_expiration is an attribute of an OPTION only — the timestamp at which a soft hold
     * lapses. A RESERVATION/SERVICE/FREE/UNKNOWN row must NEVER carry one. Partner occupancy feeds
     * echo a stale optionValidTill / optionExpirationDate when an OPTION is confirmed into a
     * RESERVATION (the same external row flips type but keeps the old expiry), so the availability
     * sync must clamp the value to OPTION rows here. Persisting it on a RESERVATION creates a
     * "zombie" (status=RESERVATION + past option_expiration, future dateTo) that hard-blocks a boat
     * the partner has already freed and is invisible to both the OPTION purge (wrong status) and
     * read-time option-honesty (only releases OPTION). Discovered 2026-06-25: 131k zombie rows.
     */
    fun clampOptionExpiration(rawExpiration: LocalDateTime?): LocalDateTime? =
        if (this == OPTION) rawExpiration else null

    companion object {
        fun fromNausysValue(value: RestYachtReservationOccupancy.ReservationType?): ExternalReservationStatus {
            return when (value) {
                RestYachtReservationOccupancy.ReservationType.OPTION -> OPTION
                RestYachtReservationOccupancy.ReservationType.RESERVATION -> RESERVATION
                RestYachtReservationOccupancy.ReservationType.SERVICE -> SERVICE
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: Long?): ExternalReservationStatus {
            // MMK status codes - canonical meaning documented on OfferStatus.fromMmkValue.
            // external_reservations only needs the BUSY truth: block 1/4/6/10/11, soft-hold 2/9,
            // everything-available -> FREE (no-op). Codes 6/10/11 (owner-week/regatta/sleep-aboard)
            // and 8 previously fell to UNKNOWN (no-op) and leaked as bookable; 6/10/11 now block via
            // SERVICE, while 8 (custom internal, "boat available") stays FREE here (the offer row may
            // still be UNAVAILABLE via OfferStatus.fromMmkValue, which the matview pre-filter blocks).
            return when (value?.toInt()) {
                1 -> RESERVATION // reservation - booked
                2 -> OPTION // option - soft hold (visible, inquiry-only)
                9 -> OPTION // option on waiting - second option, treat as soft hold
                4 -> SERVICE // service / maintenance - not bookable
                6 -> SERVICE // owner week - not bookable
                10 -> SERVICE // regatta - not bookable
                11 -> SERVICE // sleep aboard - not bookable
                0 -> FREE // free
                3 -> FREE // option expired - available
                5 -> FREE // cancelled - available
                7 -> FREE // offer sent - available
                8 -> FREE // custom internal use - available
                else -> UNKNOWN // null / unmapped
            }
        }
    }
}
