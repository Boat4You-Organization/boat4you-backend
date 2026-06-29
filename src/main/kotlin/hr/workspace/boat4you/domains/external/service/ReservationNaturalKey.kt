package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import java.time.LocalDate

/**
 * Identity of a partner reservation INDEPENDENT of external-id mapping integrity.
 *
 * The availability reconcile matches OUR reservations to the partner's COMPLETE response by this
 * natural key (our yacht id + dates + status) instead of by the `external_mapping`. A stale
 * (cancelled-at-partner) row is therefore removed even when its mapping is missing (96k legacy
 * rows) or duplicate-mapped to another yacht (the Vi La Ut case) — the two failure modes that made
 * the old id-based reconcile leave stale RESERVATION rows immortal and hide free yachts.
 *
 * INVARIANT: both sides MUST build the key through the SAME date/status conversion the upsert uses
 * (NauSys `periodFrom?.value`, MMK `dateFrom?.value?.toLocalDate()`, status via
 * `fromNausysValue`/`fromMmkValue`). A just-synced VALID row is then byte-identical to its
 * response key and can never look "absent" → no false deletion. Mario 29.6.2026.
 */
data class ReservationNaturalKey(
    val yachtId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val status: ExternalReservationStatus,
) {
    companion object {
        /** Returns null if any component is null (the row/record is dropped from the key set). */
        fun of(
            yachtId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
            status: ExternalReservationStatus?,
        ): ReservationNaturalKey? {
            if (yachtId == null || dateFrom == null || dateTo == null || status == null) return null
            return ReservationNaturalKey(yachtId, dateFrom, dateTo, status)
        }
    }
}
