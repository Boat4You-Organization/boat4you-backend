package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus

/**
 * One charter period must speak with ONE voice — one pick-up location, one
 * price row (Mario 14.9.2026).
 *
 * The same yacht-week legitimately carries several offer rows: partners sell
 * one-way variants between neighbouring bases (Nela: Nikiana / Lefkas / Sami,
 * all return charters the same week), and on top of that a base change writes
 * a NEW row while the superseded one lives on for ever — the route is part of
 * the offer upsert key and nothing deletes what the partner stopped sending
 * (LODIRE 14.9.2026: the week of 26.9 showed two identical 6.318 EUR rows, one
 * of them departing Alimos/Athens ~300 km from where the boat actually was).
 *
 * Picking order, most trustworthy signal first:
 *  1. bookable rows (FREE) — a client can only act on those;
 *  2. round trips (pick-up == drop-off) — where the boat physically sits;
 *  3. the home base among them, so a yacht offered from several bases keeps a
 *     stable label instead of hopping between equally valid marinas;
 *  4. highest id as the final tie-break.
 *
 * Row age deliberately does NOT decide: an upsert never changes the id, so it
 * marks when a row was FIRST created, not how fresh it is — verified 14.9 on
 * LODIRE's 24.10 week, where the stale Skiathos>Alimos row outranks the valid
 * Alimos>Alimos one.
 */
fun pickOfferForPeriod(offers: List<OfferDto>, homeBaseId: String?): OfferDto? {
    if (offers.size <= 1) return offers.firstOrNull()

    val bookable = offers.filter { it.status == ExternalReservationStatus.FREE }.ifEmpty { offers }
    val roundTrips = bookable.filter { it.locationFrom?.id != null && it.locationFrom?.id == it.locationTo?.id }
    val candidates = roundTrips.ifEmpty { bookable }

    return candidates.firstOrNull { it.locationFrom?.id == homeBaseId }
        ?: candidates.maxByOrNull { it.id ?: 0L }
}
