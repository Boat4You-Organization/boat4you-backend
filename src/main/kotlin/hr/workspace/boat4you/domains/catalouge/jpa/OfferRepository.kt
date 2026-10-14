package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import jakarta.persistence.LockModeType
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

@Suppress("TooManyFunctions") // one repository per entity; every query is a named, documented native statement
interface OfferRepository : JpaRepository<Offer, Long> {
    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe 
        LEFT JOIN FETCH oe.extras
        WHERE o.id = :id
    """,
    )
    fun findByIdWithEagerLoad(id: Long): Offer?

    /**
     * Pessimistic-write lock on one offer row for the booking re-check (Deploy 3):
     * serialises concurrent reservation attempts against the same offer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Offer o WHERE o.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Offer?

    fun findAllByYacht(yacht: Yacht): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        WHERE o.yacht.agency.id = :agencyId
    """,
    )
    fun findAllByYachtAgencyId(
        @Param("agencyId") agencyId: Long,
    ): List<Offer>

    /**
     * Cacheable: sequential requests for the same (yacht, statuses)
     * combo over a single TTL window can reuse the prior result —
     * the offer table changes via NauSys/MMK sync which @CacheEvicts.
     *
     * F2-025: explicit SpEL key. Default Spring behaviour wraps the
     * args in a SimpleKey whose hashCode is computed from
     * `yacht.hashCode()` and `statuses.hashCode()`. Yacht has no
     * id-based equals (F2-017 family) so its hashCode is reference
     * identity — every request loads a fresh instance and the cache
     * never hits. The SpEL key composes yacht.id with the structural
     * Set.hashCode of the statuses so two requests with the same
     * yacht and same status set land on the same cache entry.
     */
    @Cacheable("offersByYachtAndStatusCache", key = "#yacht.id + ':' + #statuses.hashCode()")
    @Query(
        """
        SELECT o FROM Offer o
        WHERE o.yacht = :yacht
        AND o.status IN :statuses
    """,
    )
    fun findAllAvailableByYacht(
        yacht: Yacht,
        statuses: Set<OfferStatus>,
    ): List<Offer>

    fun findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht = :yacht
        AND o.dateFrom = :dateFrom
        AND o.dateTo = :dateTo
        ORDER BY o.id ASC
    """,
    )
    fun findAllByYachtAndDateFromAndDateTo(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    /**
     * Offers whose week OVERLAPS the half-open interval [dateFrom, dateTo). Half-open so a
     * charter that ends on the same day the next begins (Saturday turnaround) is NOT counted
     * as a conflict. Used by availability sync to mark EVERY week a reservation touches as
     * UNAVAILABLE — including multi-week and non-Saturday-aligned reservations that an exact
     * dateFrom/dateTo match silently leaves bookable (the over-availability bug).
     */
    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht = :yacht
        AND o.dateFrom < :dateTo
        AND o.dateTo > :dateFrom
        ORDER BY o.id ASC
    """,
    )
    fun findAllByYachtAndDateRangeOverlap(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht.id = :yachtId
        AND o.dateFrom >= :dateFrom
        AND o.dateTo <= :dateTo
        AND o.type = :offerType
        ORDER BY o.dateFrom, o.clientPrice
    """,
    )
    fun findOffersByYachtIdAndDateFromAndDateToAndOfferType(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("offerType") offerType: OfferType,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerPaymentPlans
        WHERE o.yacht.id = :yachtId
        AND o.dateFrom = :dateFrom
        AND o.dateTo = :dateTo
        ORDER BY o.id ASC
    """,
    )
    fun findByYachtIdAndDatesWithPaymentPlans(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): List<Offer>

    // deleteExpiredOffers removed 12.7.2026 — expired-offer retention is owned by
    // RetentionReaperJob (batched, per-batch commits). Keeping a second delete path
    // here invites the giant-single-tx failure mode back.

    /**
     * Drop SYNTHETIC_OPTION offers (availability sync creates these to surface a boat held under
     * another agent's option) that are no longer backed by a LIVE option — the option expired or
     * the partner dropped it. Set-based + guarded like deleteExpiredOffers: never touches an offer
     * a reservation_flow points at (a booking in progress) so it can't FK-violate or delete a
     * booked offer. A synthetic offer survives only while a non-expired OPTION external_reservations
     * row still overlaps its week (half-open overlap).
     */
    @Modifying
    @Query(
        """
        DELETE FROM offer o
        WHERE o.ext_status = 'SYNTHETIC_OPTION'
        AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
        AND NOT EXISTS (
            SELECT 1 FROM external_reservations r
            WHERE r.yacht_id = o.yacht_id
            AND r.status = 'OPTION'
            AND r.option_expiration > now()
            AND r.date_from < o.date_to AND r.date_to > o.date_from
        )
        """,
        nativeQuery = true,
    )
    fun deleteUnbackedSyntheticOptionOffers(): Int

    /**
     * Future UNAVAILABLE weeks of active MMK-primary yachts with NO partner reservation/service
     * blocking them in the mirror — i.e. weeks we show as taken that the partner may actually
     * still sell. These are the reverify candidates for [MmkStaleOfferReverifyService]: the
     * yearly agency sweep response is not a complete picture (proven 20.7.2026), so only a
     * per-yacht exact-date MMK call may decide such a week's real status. Distinct per
     * yacht+dates: one partner call covers every product/route row of that week.
     */
    @Query(
        """
        SELECT DISTINCT o.yacht_id AS "yachtId", em.external_id AS "externalYachtId",
               y.agency_id AS "agencyId", o.date_from AS "dateFrom", o.date_to AS "dateTo"
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id AND y.sys_active = true
        JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false
        JOIN agency_source s ON s.agency_id = a.id AND s."primary" = true AND s.external_system_id = :mmkSystemId
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :mmkSystemId
             AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        WHERE o.status = 'UNAVAILABLE'
        AND o.date_from >= CURRENT_DATE
        AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION')
        AND NOT EXISTS (
            SELECT 1 FROM external_reservations r
            WHERE r.yacht_id = o.yacht_id
            AND r.status IN ('RESERVATION','SERVICE')
            AND r.date_from < o.date_to AND r.date_to > o.date_from
        )
        -- A week the free-offer reverifier hid is re-asked weekly, not nightly: a withdrawn price list does not
        -- come back overnight, and 10k such weeks would add ~35 min to every run (V9_59).
        AND NOT EXISTS (
            SELECT 1 FROM mmk_free_week_strike k
            WHERE k.yacht_id = o.yacht_id AND k.date_from = o.date_from AND k.date_to = o.date_to
            AND k.hidden_on IS NOT NULL AND k.hidden_on > CURRENT_DATE - 7
        )
        ORDER BY "dateFrom", "yachtId"
        """,
        nativeQuery = true,
    )
    fun findStaleUnavailableMmkCombos(
        @Param("mmkSystemId") mmkSystemId: Long,
    ): List<StaleMmkOfferCombo>

    /**
     * Mirror image of [findStaleUnavailableMmkCombos]: future FREE rows of any length of active MMK-primary yachts, and
     * OPTION rows that neither a live partner option or booking nor our own reservation flow backs (the read path
     * demotes those to FREE: OfferQueryingService, OPTION_ECHO_GRACE_HOURS). OPTION_WAITING is left alone on purpose
     * (pre-reserved, owner's rule). These are the candidates for
     * [MmkFreeOfferReverifyService] — since the agency-level yearly sweep became upsert-only (20.7.2026) nothing
     * removes an offer the partner no longer sells (a withdrawn next-season price list, 21.9.2026: 10,375 rows on
     * 189 yachts; a 28-night block MMK stopped quoting, EMA 12210, 26.9.2026). Each row is probed with its OWN dates
     * and only rows with exactly those dates are flipped (evidence scope == write scope). Distinct per yacht+dates:
     * one partner call covers every product/route row of that period.
     */
    @Query(
        """
        SELECT DISTINCT o.yacht_id AS "yachtId", em.external_id AS "externalYachtId",
               y.agency_id AS "agencyId", o.date_from AS "dateFrom", o.date_to AS "dateTo"
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id AND y.sys_active = true
        JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false
        JOIN agency_source s ON s.agency_id = a.id AND s."primary" = true AND s.external_system_id = :mmkSystemId
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :mmkSystemId
             AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        WHERE o.date_from >= CURRENT_DATE
        AND o.date_to > o.date_from
        AND (
            o.status = 'FREE'
            OR (
                o.status = 'OPTION'
                AND NOT EXISTS (
                    SELECT 1 FROM external_reservations r
                    WHERE r.yacht_id = o.yacht_id
                    AND r.date_from < o.date_to AND r.date_to > o.date_from
                    AND (
                        r.status IN ('RESERVATION','SERVICE')
                        OR (r.status = 'OPTION' AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours'))
                    )
                )
                AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
            )
        )
        ORDER BY "yachtId", "dateFrom"
        """,
        nativeQuery = true,
    )
    fun findShownFreeMmkCombos(
        @Param("mmkSystemId") mmkSystemId: Long,
    ): List<StaleMmkOfferCombo>

    /**
     * Hides the rows of ONE probed period (all its product/route variants): FREE rows, and OPTION rows no live
     * option, booking or reservation flow backs — exactly the rows [findShownFreeMmkCombos] offered and the probe
     * asked MMK about, re-checked at write time. Never a row with other dates that merely overlaps (the 20.7.2026
     * lesson: a fate decided on evidence never gathered for that row), never an option the partner or our customer
     * still holds. Returns the number of rows flipped.
     */
    @Modifying
    @Query(
        """
        UPDATE offer o SET status = 'UNAVAILABLE'
        WHERE o.yacht_id = :yachtId AND o.date_from = :dateFrom AND o.date_to = :dateTo
        AND (
            o.status = 'FREE'
            OR (
                o.status = 'OPTION'
                AND NOT EXISTS (
                    SELECT 1 FROM external_reservations r
                    WHERE r.yacht_id = o.yacht_id
                    AND r.date_from < o.date_to AND r.date_to > o.date_from
                    AND (
                        r.status IN ('RESERVATION','SERVICE')
                        OR (r.status = 'OPTION' AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours'))
                    )
                )
                AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
            )
        )
        """,
        nativeQuery = true,
    )
    fun markPeriodUnavailable(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): Int

    /**
     * MMK-only successor of [markPeriodUnavailable] (real-price DESIGN.md WS-B "Common machinery"): hides the rows of
     * ONE probed period and writes one `partner_offer_flip_log` row per flipped offer IN THE SAME STATEMENT (a CTE),
     * so a flip without its log row (or the reverse) cannot exist and every flip can be undone exactly
     * (PartnerFlipUndoService). Write scope, re-checked at write time under a row lock:
     *  - FREE rows;
     *  - OPTION rows that no live partner hold (RESERVATION/SERVICE, OPTION within the 48 h echo grace) and no
     *    reservation_flow back;
     *  - OPTION_WAITING rows under the same guards ONLY when [includeOptionWaiting] (MMK_OPTION_VERIFY=ON): two empty
     *    exact-date answers with `showOptions = true` on different days are partner evidence that nobody holds it;
     *  - never a SYNTHETIC_OPTION row, never a row with other dates (evidence scope == write scope).
     * NauSys has its own query (WS-B2) so the two partners never widen each other's scope.
     *
     * @return the number of offer rows flipped (= flip-log rows written).
     */
    @Modifying
    @Query(
        "WITH cand AS (SELECT o.id, o.status, o.ext_status, o.client_price FROM offer o WHERE $MMK_FLIPPABLE_PERIOD_ROWS " +
            "FOR UPDATE OF o), " +
            "upd AS (UPDATE offer o SET status = 'UNAVAILABLE' FROM cand WHERE o.id = cand.id RETURNING o.id) " +
            "INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, " +
            "date_to, old_status, old_ext_status, old_client_price, new_status, first_empty_on, decided_on) " +
            "SELECT CAST(:runId AS BIGINT), :job, :reason, 1, cand.id, :yachtId, :dateFrom, :dateTo, cand.status, " +
            "cand.ext_status, cand.client_price, 'UNAVAILABLE', CAST(:firstEmptyOn AS DATE), :decidedOn " +
            "FROM cand JOIN upd USING (id)",
        nativeQuery = true,
    )
    @Suppress("LongParameterList") // the exact period, the run it belongs to and the evidence it was decided on
    fun markMmkPeriodUnavailable(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("runId") runId: Long?,
        @Param("job") job: String,
        @Param("reason") reason: String,
        @Param("firstEmptyOn") firstEmptyOn: LocalDate?,
        @Param("decidedOn") decidedOn: LocalDate,
        @Param("includeOptionWaiting") includeOptionWaiting: Boolean,
    ): Int

    /** The rows [markMmkPeriodUnavailable] would flip right now (LOG mode would-flip count); nothing is written. */
    @Query(
        "SELECT count(*) FROM offer o WHERE $MMK_FLIPPABLE_PERIOD_ROWS",
        nativeQuery = true,
    )
    fun countMmkPeriodFlippable(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("includeOptionWaiting") includeOptionWaiting: Boolean,
    ): Int

    /**
     * Step 2c of the MMK free-offer reverifier (DESIGN.md B1): future OPTION / OPTION_WAITING periods of active
     * MMK-primary yachts that no live partner hold (48 h echo grace) and no reservation_flow back, never
     * SYNTHETIC_OPTION, distinct per yacht + exact dates, rotated on `partner_period_check` (MMK): periods checked on or
     * after [checkedBefore] are skipped, never-checked periods come first, then the oldest check, then the earliest
     * period. Bounded by [limit]; the rotation drains the tail over a few runs and then re-checks every period weekly.
     */
    @Query(
        """
        SELECT DISTINCT o.yacht_id AS "yachtId", em.external_id AS "externalYachtId",
               y.agency_id AS "agencyId", o.date_from AS "dateFrom", o.date_to AS "dateTo",
               c.last_checked_on AS "lastCheckedOn"
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id AND y.sys_active = true
        JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false
        JOIN agency_source s ON s.agency_id = a.id AND s."primary" = true AND s.external_system_id = :mmkSystemId
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :mmkSystemId
             AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        LEFT JOIN partner_period_check c ON c.external_system_id = :mmkSystemId AND c.yacht_id = o.yacht_id
             AND c.date_from = o.date_from AND c.date_to = o.date_to
        WHERE o.date_from >= :today
        AND o.date_to > o.date_from
        AND o.status IN ('OPTION', 'OPTION_WAITING')
        AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION')
        AND NOT EXISTS (
            SELECT 1 FROM external_reservations r
            WHERE r.yacht_id = o.yacht_id
            AND r.date_from < o.date_to AND r.date_to > o.date_from
            AND (
                r.status IN ('RESERVATION','SERVICE')
                OR (r.status = 'OPTION' AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours'))
            )
        )
        AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
        AND (c.last_checked_on IS NULL OR c.last_checked_on < :checkedBefore)
        ORDER BY "lastCheckedOn" NULLS FIRST, "dateFrom", "yachtId"
        LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findStaleMmkOptionPeriods(
        @Param("mmkSystemId") mmkSystemId: Long,
        @Param("today") today: LocalDate,
        @Param("checkedBefore") checkedBefore: LocalDate,
        @Param("limit") limit: Int,
    ): List<StaleMmkOfferCombo>

    /**
     * Step 2b of the MMK free-offer reverifier: open strikes in `mmk_free_week_strike` (struck on an earlier day, not
     * hidden; e.g. written by the on-demand path) of future periods of active MMK-primary yachts that no probe decided
     * today (`partner_period_check`), oldest strike first.
     */
    @Query(
        """
        SELECT k.yacht_id AS "yachtId", em.external_id AS "externalYachtId", y.agency_id AS "agencyId",
               k.date_from AS "dateFrom", k.date_to AS "dateTo"
        FROM mmk_free_week_strike k
        JOIN yacht y ON y.id = k.yacht_id AND y.sys_active = true
        JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false
        JOIN agency_source s ON s.agency_id = a.id AND s."primary" = true AND s.external_system_id = :mmkSystemId
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :mmkSystemId
             AND em.system_id = k.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        WHERE k.hidden_on IS NULL
        AND k.first_empty_on < :today
        AND k.date_from >= :today
        AND NOT EXISTS (
            SELECT 1 FROM partner_period_check c
            WHERE c.external_system_id = :mmkSystemId AND c.yacht_id = k.yacht_id
            AND c.date_from = k.date_from AND c.date_to = k.date_to AND c.last_checked_on >= :today
        )
        ORDER BY k.first_empty_on, k.yacht_id, k.date_from
        LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findOpenMmkStrikePeriods(
        @Param("mmkSystemId") mmkSystemId: Long,
        @Param("today") today: LocalDate,
        @Param("limit") limit: Int,
    ): List<StaleMmkOfferCombo>

    /** Status and partner total of every row of exactly this period (MMK drift check, DESIGN.md B1 path 8). */
    @Query(
        """
        SELECT o.status AS "status", o.ext_total_price AS "extTotalPrice"
        FROM offer o WHERE o.yacht_id = :yachtId AND o.date_from = :dateFrom AND o.date_to = :dateTo
        """,
        nativeQuery = true,
    )
    fun findPeriodStatusPrices(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): List<OfferStatusPrice>

    /**
     * Our yachts behind one MMK yacht id that show a row (FREE / OPTION / OPTION_WAITING, not SYNTHETIC_OPTION) with
     * exactly these dates: the on-demand path records a strike only for a period the site actually shows.
     */
    @Query(
        """
        SELECT DISTINCT o.yacht_id
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id
        JOIN agency_source s ON s.agency_id = y.agency_id AND s."primary" = true AND s.external_system_id = :mmkSystemId
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :mmkSystemId
             AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        WHERE em.external_id = :externalYachtId
        AND o.date_from = :dateFrom AND o.date_to = :dateTo
        AND o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
        AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION')
        """,
        nativeQuery = true,
    )
    fun findYachtIdsShowingMmkPeriod(
        @Param("mmkSystemId") mmkSystemId: Long,
        @Param("externalYachtId") externalYachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): List<Long>

    /**
     * NauSys-only write query (real-price DESIGN.md WS-B2): hides rows of ONE exact period and writes one
     * `partner_offer_flip_log` row per flipped offer IN THE SAME STATEMENT (CTE, like [markMmkPeriodUnavailable]).
     * With [offerId] only that row of the period is a candidate (a stale route variant); null = every row of the period.
     *
     * Write scope, re-checked at write time under a row lock ([NAUSYS_FLIPPABLE_PERIOD_ROWS]):
     *  - FREE rows; OPTION rows; OPTION_WAITING rows ONLY with our own `ext_status = 'SYNTHETIC_DISAPPEARANCE'` marker;
     *  - never a real NauSys `UNDER_OPTION` row, never `SYNTHETIC_OPTION`, never a row a `reservation_flow` points at,
     *    never a row with other dates;
     *  - [occupancy] false (two-strike / route / unpriced evidence): OPTION and the marker only without a live partner
     *    hold (RESERVATION / SERVICE, OPTION within the 48 h echo grace);
     *  - [occupancy] true (step 0): only rows a live RESERVATION / SERVICE overlaps (zombie escape: a row whose
     *    `option_expiration` has passed does not count); a live partner OPTION alone leaves the row.
     *
     * @return the number of offer rows flipped (= flip-log rows written).
     */
    @Modifying
    @Query(
        "WITH cand AS (SELECT o.id, o.status, o.ext_status, o.client_price FROM offer o WHERE $NAUSYS_FLIPPABLE_PERIOD_ROWS " +
            "FOR UPDATE OF o), " +
            "upd AS (UPDATE offer o SET status = 'UNAVAILABLE' FROM cand WHERE o.id = cand.id RETURNING o.id) " +
            "INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, " +
            "date_to, old_status, old_ext_status, old_client_price, new_status, first_empty_on, decided_on) " +
            "SELECT CAST(:runId AS BIGINT), :job, :reason, 2, cand.id, :yachtId, :dateFrom, :dateTo, cand.status, " +
            "cand.ext_status, cand.client_price, 'UNAVAILABLE', CAST(:firstEmptyOn AS DATE), :decidedOn " +
            "FROM cand JOIN upd USING (id)",
        nativeQuery = true,
    )
    @Suppress("LongParameterList") // the exact period (and row), the guard variant, the run and the evidence
    fun markNauSysPeriodUnavailable(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("offerId") offerId: Long?,
        @Param("occupancy") occupancy: Boolean,
        @Param("runId") runId: Long?,
        @Param("job") job: String,
        @Param("reason") reason: String,
        @Param("firstEmptyOn") firstEmptyOn: LocalDate?,
        @Param("decidedOn") decidedOn: LocalDate,
    ): Int

    /** The rows [markNauSysPeriodUnavailable] would flip right now (LOG-mode would-flip count); nothing is written. */
    @Query(
        "SELECT count(*) FROM offer o WHERE $NAUSYS_FLIPPABLE_PERIOD_ROWS",
        nativeQuery = true,
    )
    fun countNauSysPeriodFlippable(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("offerId") offerId: Long?,
        @Param("occupancy") occupancy: Boolean,
    ): Int

    /**
     * Candidates of the NauSys free-offer reverifier (DESIGN.md B2): future periods of active NauSys-primary yachts
     * (agency active, `availability_blocked = false`), never SYNTHETIC_OPTION, one row per class and exact period,
     * rotated on `partner_period_check` (NauSys) and capped per class. Periods hidden by us in the last 7 days are
     * skipped. `cls` = [hr.workspace.boat4you.domains.external.nausys.service.NauSysVerifyClass.code]:
     *  - 1 P1 our SYNTHETIC_DISAPPEARANCE marker (OPTION_WAITING) without a reservation_flow;
     *  - 2 P2 OPTION / OPTION_WAITING without a live hold (48 h) or reservation_flow, `ext_status` not UNDER_OPTION,
     *    SYNTHETIC_OPTION or SYNTHETIC_DISAPPEARANCE;
     *  - 3 P2b real UNDER_OPTION rows the partner has not quoted for 7 days (evidence only, never flipped);
     *  - 4 P3 open strikes (not decided today) and never-checked periods queued with priority <= 3;
     *  - 5 P4 FREE rows off the nightly grid (not Sat->Sat 7 nights, not Sat->Sat of the season's minimal duration);
     *  - 6 P5 grid FREE rows: the first, middle and last eligible period per yacht and year;
     *  - 7 P6 UNAVAILABLE rows no live RESERVATION / SERVICE overlaps (zombie escape) - the stale-UNAVAILABLE healer.
     * Rotation: P1/P2/P2b/P6 skip periods decided in the last 7 days, P4/P5 in the last 14 days, P3 only today.
     * Within a class: lowest stored priority, never-checked, oldest check, earliest period first.
     */
    @Query(
        """
        SELECT k.yacht_id AS "yachtId", k.external_id AS "externalYachtId", k.agency_id AS "agencyId",
               k.date_from AS "dateFrom", k.date_to AS "dateTo", k.cls AS "cls",
               (SELECT CAST(max(ro.minimal_duration) AS INTEGER) FROM reservation_options ro
                 WHERE ro.yacht_id = k.yacht_id AND ro.date_from <= k.date_from
                 AND (ro.date_to IS NULL OR ro.date_to >= k.date_from)) AS "seasonMinDuration",
               (SELECT string_agg(x.id || ':' || x.location_from || '>' || x.location_to, ',' ORDER BY x.id) FROM offer x
                 WHERE x.yacht_id = k.yacht_id AND x.date_from = k.date_from AND x.date_to = k.date_to
                 AND x.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
                 AND (x.ext_status IS NULL OR x.ext_status <> 'SYNTHETIC_OPTION')) AS "shownRows"
        FROM (
            ($NAUSYS_CAND_SELECT, 1 AS cls $NAUSYS_CAND_FROM
             AND o.status = 'OPTION_WAITING' AND o.ext_status = 'SYNTHETIC_DISAPPEARANCE'
             AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
             AND $NAUSYS_SKIP_7 $NAUSYS_CAND_ORDER LIMIT :capP1)
            UNION ALL
            ($NAUSYS_CAND_SELECT, 2 AS cls $NAUSYS_CAND_FROM
             AND o.status IN ('OPTION', 'OPTION_WAITING')
             AND (o.ext_status IS NULL OR o.ext_status NOT IN ('UNDER_OPTION', 'SYNTHETIC_OPTION', 'SYNTHETIC_DISAPPEARANCE'))
             AND NOT $NAUSYS_LIVE_HOLD_48H
             AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
             AND $NAUSYS_SKIP_7 $NAUSYS_CAND_ORDER LIMIT :capP2)
            UNION ALL
            ($NAUSYS_CAND_SELECT, 3 AS cls $NAUSYS_CAND_FROM
             AND o.status = 'OPTION_WAITING' AND o.ext_status = 'UNDER_OPTION'
             AND (c.last_quoted_on IS NULL OR c.last_quoted_on < CAST(:today AS DATE) - 7)
             AND $NAUSYS_SKIP_7 $NAUSYS_CAND_ORDER LIMIT :capP2b)
            UNION ALL
            ($NAUSYS_CAND_SELECT, 4 AS cls $NAUSYS_CAND_FROM
             AND o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
             AND c.hidden_on IS NULL
             AND (c.first_empty_on IS NOT NULL OR (c.priority <= 3 AND c.last_checked_on IS NULL))
             AND (c.last_checked_on IS NULL OR c.last_checked_on < :today) $NAUSYS_CAND_ORDER LIMIT :capP3)
            UNION ALL
            ($NAUSYS_CAND_SELECT, 5 AS cls $NAUSYS_CAND_FROM
             AND o.status = 'FREE' AND NOT $NAUSYS_GRID_ROW
             AND $NAUSYS_SKIP_14 $NAUSYS_CAND_ORDER LIMIT :capP4)
            UNION ALL
            (SELECT w.yacht_id, w.external_id, w.agency_id, w.date_from, w.date_to, w.prio, w.last_checked_on, 6 AS cls
             FROM (SELECT g.*,
                          row_number() OVER (PARTITION BY g.yacht_id, EXTRACT(YEAR FROM g.date_from) ORDER BY g.date_from, g.date_to) AS rn,
                          count(*) OVER (PARTITION BY g.yacht_id, EXTRACT(YEAR FROM g.date_from)) AS cnt
                   FROM ($NAUSYS_CAND_SELECT $NAUSYS_CAND_FROM
                         AND o.status = 'FREE' AND $NAUSYS_GRID_ROW AND $NAUSYS_SKIP_14) g) w
             WHERE w.rn = 1 OR w.rn = w.cnt OR w.rn = (w.cnt + 1) / 2
             ORDER BY w.prio, w.last_checked_on NULLS FIRST, w.date_from, w.yacht_id LIMIT :capP5)
            UNION ALL
            ($NAUSYS_CAND_SELECT, 7 AS cls $NAUSYS_CAND_FROM
             AND o.status = 'UNAVAILABLE' AND NOT $NAUSYS_LIVE_OCCUPANCY
             AND $NAUSYS_SKIP_7 $NAUSYS_CAND_ORDER LIMIT :capP6)
        ) k
        """,
        nativeQuery = true,
    )
    @Suppress("LongParameterList") // the partner, the run date and one cap per class
    fun findNauSysVerifyCandidates(
        @Param("sys") nausysSystemId: Long,
        @Param("today") today: LocalDate,
        @Param("capP1") capP1: Int,
        @Param("capP2") capP2: Int,
        @Param("capP2b") capP2b: Int,
        @Param("capP3") capP3: Int,
        @Param("capP4") capP4: Int,
        @Param("capP5") capP5: Int,
        @Param("capP6") capP6: Int,
    ): List<NauSysVerifyCandidate>

    /**
     * Step 0 of the NauSys reverifier: future periods of active NauSys-primary yachts with a row
     * [markNauSysPeriodUnavailable] would flip on occupancy (a live RESERVATION / SERVICE overlaps it, zombie escape;
     * no reservation_flow, never SYNTHETIC_OPTION / UNDER_OPTION), distinct per yacht + exact dates, earliest first.
     */
    @Query(
        """
        SELECT DISTINCT o.yacht_id AS "yachtId", y.agency_id AS "agencyId", o.date_from AS "dateFrom", o.date_to AS "dateTo"
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id AND y.sys_active = true
        JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false
        JOIN agency_source s ON s.agency_id = a.id AND s."primary" = true AND s.external_system_id = :sys
        WHERE o.date_from >= :today AND o.date_to > o.date_from
        AND (o.ext_status IS NULL OR o.ext_status NOT IN ('SYNTHETIC_OPTION', 'UNDER_OPTION'))
        AND (o.status IN ('FREE', 'OPTION') OR (o.status = 'OPTION_WAITING' AND o.ext_status = 'SYNTHETIC_DISAPPEARANCE'))
        AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
        AND $NAUSYS_LIVE_OCCUPANCY
        ORDER BY "dateFrom", "yachtId"
        LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findNauSysOccupiedShownPeriods(
        @Param("sys") nausysSystemId: Long,
        @Param("today") today: LocalDate,
        @Param("limit") limit: Int,
    ): List<NauSysPeriod>

    /**
     * Path 4 (NauSys on-demand warm): the rows we show (FREE / OPTION / OPTION_WAITING, not SYNTHETIC_OPTION) with
     * exactly these dates on our yachts behind one NauSys yacht id of a NauSys-primary agency, with their route.
     */
    @Query(
        """
        SELECT o.id AS "offerId", o.yacht_id AS "yachtId", o.location_from AS "locationFromId", o.location_to AS "locationToId"
        FROM offer o
        JOIN yacht y ON y.id = o.yacht_id
        JOIN agency_source s ON s.agency_id = y.agency_id AND s."primary" = true AND s.external_system_id = :sys
        JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :sys
             AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id
        WHERE em.external_id = :externalYachtId
        AND o.date_from = :dateFrom AND o.date_to = :dateTo
        AND o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
        AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION')
        ORDER BY o.id
        """,
        nativeQuery = true,
    )
    fun findNauSysShownPeriodRows(
        @Param("sys") nausysSystemId: Long,
        @Param("externalYachtId") externalYachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): List<NauSysShownRow>

    /**
     * Purge hook (DESIGN.md B2): before [ExternalReservationRepository.deleteExpiredOptions] drops the lapsed holds,
     * queue every future OPTION offer of a NauSys-primary yacht that overlaps one of them for a partner re-check
     * (`partner_period_check`, NauSys, [priority]; an existing row only has its priority lowered). Never writes an
     * offer. Returns the number of periods inserted or re-prioritised.
     */
    @Modifying
    @Query(
        """
        INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, priority)
        SELECT DISTINCT CAST(:sys AS SMALLINT), o.yacht_id, o.date_from, o.date_to, CAST(:priority AS SMALLINT)
        FROM external_reservations r
        JOIN yacht y ON y.id = r.yacht_id
        JOIN agency_source s ON s.agency_id = y.agency_id AND s."primary" = true AND s.external_system_id = :sys
        JOIN offer o ON o.yacht_id = r.yacht_id AND o.date_from < r.date_to AND o.date_to > r.date_from
        WHERE r.status IN ('OPTION', 'RESERVATION') AND r.option_expiration < :cutoff
        AND o.status = 'OPTION' AND o.date_from >= CURRENT_DATE
        AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION')
        ON CONFLICT (external_system_id, yacht_id, date_from, date_to)
        DO UPDATE SET priority = LEAST(partner_period_check.priority, EXCLUDED.priority)
        """,
        nativeQuery = true,
    )
    fun queueNauSysOptionsUnderExpiringHolds(
        @Param("sys") nausysSystemId: Long,
        @Param("cutoff") cutoff: java.time.LocalDateTime,
        @Param("priority") priority: Int,
    ): Int

    /** True when a booking in progress (`reservation_flow`) points at the offer (D3 legacy-branch guard). */
    @Query("SELECT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = :offerId)", nativeQuery = true)
    fun existsReservationFlowForOffer(
        @Param("offerId") offerId: Long,
    ): Boolean
}

/**
 * Write scope of [OfferRepository.markMmkPeriodUnavailable] / [OfferRepository.countMmkPeriodFlippable] (alias `o`,
 * params :yachtId, :dateFrom, :dateTo, :includeOptionWaiting). One text for both, so the LOG-mode would-flip count
 * can never disagree with what WRITE mode flips.
 */
private const val MMK_FLIPPABLE_PERIOD_ROWS =
    "o.yacht_id = :yachtId AND o.date_from = :dateFrom AND o.date_to = :dateTo " +
        "AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION') " +
        "AND (o.status = 'FREE' OR ((o.status = 'OPTION' OR (CAST(:includeOptionWaiting AS BOOLEAN) AND o.status = 'OPTION_WAITING')) " +
        "AND NOT EXISTS (SELECT 1 FROM external_reservations r WHERE r.yacht_id = o.yacht_id " +
        "AND r.date_from < o.date_to AND r.date_to > o.date_from " +
        "AND (r.status IN ('RESERVATION','SERVICE') OR (r.status = 'OPTION' " +
        "AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours')))) " +
        "AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)))"

/** Native projection for [OfferRepository.findPeriodStatusPrices]. */
interface OfferStatusPrice {
    val status: String
    val extTotalPrice: java.math.BigDecimal?
}

/**
 * Native projection for [OfferRepository.findStaleUnavailableMmkCombos] and [OfferRepository.findShownFreeMmkCombos].
 */
interface StaleMmkOfferCombo {
    val yachtId: Long
    val externalYachtId: Long
    val agencyId: Long
    val dateFrom: LocalDate
    val dateTo: LocalDate
}

/** A live partner hold of the offer row `o`: RESERVATION / SERVICE, or an OPTION within the 48 h echo grace. */
private const val NAUSYS_LIVE_HOLD_48H =
    "EXISTS (SELECT 1 FROM external_reservations r WHERE r.yacht_id = o.yacht_id " +
        "AND r.date_from < o.date_to AND r.date_to > o.date_from " +
        "AND (r.status IN ('RESERVATION','SERVICE') OR (r.status = 'OPTION' " +
        "AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours'))))"

/** Live occupancy of the offer row `o`: RESERVATION / SERVICE, zombie escape (a past option expiry does not count). */
private const val NAUSYS_LIVE_OCCUPANCY =
    "EXISTS (SELECT 1 FROM external_reservations r WHERE r.yacht_id = o.yacht_id " +
        "AND r.date_from < o.date_to AND r.date_to > o.date_from AND r.status IN ('RESERVATION','SERVICE') " +
        "AND (r.option_expiration IS NULL OR r.option_expiration > NOW()))"

/**
 * Write scope of [OfferRepository.markNauSysPeriodUnavailable] / [OfferRepository.countNauSysPeriodFlippable] (alias
 * `o`, params :yachtId, :dateFrom, :dateTo, :offerId, :occupancy). One text for both, so the LOG-mode would-flip count
 * can never disagree with what WRITE mode flips. Never `UNDER_OPTION`, `SYNTHETIC_OPTION` or a reservation_flow row;
 * OPTION_WAITING only with our own SYNTHETIC_DISAPPEARANCE marker.
 */
private const val NAUSYS_FLIPPABLE_PERIOD_ROWS =
    "o.yacht_id = :yachtId AND o.date_from = :dateFrom AND o.date_to = :dateTo " +
        "AND (CAST(:offerId AS BIGINT) IS NULL OR o.id = CAST(:offerId AS BIGINT)) " +
        "AND (o.ext_status IS NULL OR o.ext_status NOT IN ('SYNTHETIC_OPTION', 'UNDER_OPTION')) " +
        "AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id) " +
        "AND (o.status IN ('FREE', 'OPTION') OR (o.status = 'OPTION_WAITING' AND o.ext_status = 'SYNTHETIC_DISAPPEARANCE')) " +
        "AND (CASE WHEN CAST(:occupancy AS BOOLEAN) THEN $NAUSYS_LIVE_OCCUPANCY " +
        "ELSE (o.status = 'FREE' OR NOT $NAUSYS_LIVE_HOLD_48H) END)"

/** Shared pieces of [OfferRepository.findNauSysVerifyCandidates] (one branch per class, `cls` follows the SELECT). */
private const val NAUSYS_CAND_SELECT =
    "SELECT DISTINCT o.yacht_id, em.external_id, y.agency_id, o.date_from, o.date_to, COALESCE(c.priority, 5) AS prio, c.last_checked_on"
private const val NAUSYS_CAND_FROM =
    "FROM offer o " +
        "JOIN yacht y ON y.id = o.yacht_id AND y.sys_active = true " +
        "JOIN agency a ON a.id = y.agency_id AND a.active = true AND a.availability_blocked = false " +
        "JOIN agency_source s ON s.agency_id = a.id AND s.\"primary\" = true AND s.external_system_id = :sys " +
        "JOIN external_mapping em ON em.type = 'Yacht' AND em.external_system_id = :sys " +
        "AND em.system_id = o.yacht_id AND em.extended_type = 'Yacht-AgencyId-' || y.agency_id " +
        "LEFT JOIN partner_period_check c ON c.external_system_id = :sys AND c.yacht_id = o.yacht_id " +
        "AND c.date_from = o.date_from AND c.date_to = o.date_to " +
        "WHERE o.date_from >= :today AND o.date_to > o.date_from " +
        "AND (o.ext_status IS NULL OR o.ext_status <> 'SYNTHETIC_OPTION') " +
        "AND (c.hidden_on IS NULL OR c.hidden_on <= CAST(:today AS DATE) - 7)"
private const val NAUSYS_SKIP_7 = "(c.last_checked_on IS NULL OR c.last_checked_on <= CAST(:today AS DATE) - 7)"
private const val NAUSYS_SKIP_14 = "(c.last_checked_on IS NULL OR c.last_checked_on <= CAST(:today AS DATE) - 14)"
private const val NAUSYS_CAND_ORDER = "ORDER BY prio, c.last_checked_on NULLS FIRST, o.date_from, o.yacht_id"

/**
 * A row of the nightly NauSys grid (ReservationOptionsCombinationProvider + YachtGroupingProvider): Saturday to
 * Saturday, 7 nights or the minimal duration of the yacht's season that covers the start.
 */
private const val NAUSYS_GRID_ROW =
    "(EXTRACT(ISODOW FROM o.date_from) = 6 AND EXTRACT(ISODOW FROM o.date_to) = 6 AND ((o.date_to - o.date_from) = 7 " +
        "OR EXISTS (SELECT 1 FROM reservation_options ro WHERE ro.yacht_id = o.yacht_id AND ro.date_from <= o.date_from " +
        "AND (ro.date_to IS NULL OR ro.date_to >= o.date_from) AND ro.minimal_duration = (o.date_to - o.date_from))))"

/** Native projection for [OfferRepository.findNauSysVerifyCandidates]. */
interface NauSysVerifyCandidate {
    val yachtId: Long
    val externalYachtId: Long
    val agencyId: Long
    val dateFrom: LocalDate
    val dateTo: LocalDate
    val cls: Int

    /** The largest minimal duration of the yacht's seasons covering the start, or null. */
    val seasonMinDuration: Int?

    /** The shown rows of the period as `offerId:locationFrom>locationTo`, comma separated, or null. */
    val shownRows: String?
}

/** Native projection for [OfferRepository.findNauSysOccupiedShownPeriods]. */
interface NauSysPeriod {
    val yachtId: Long
    val agencyId: Long
    val dateFrom: LocalDate
    val dateTo: LocalDate
}

/** Native projection for [OfferRepository.findNauSysShownPeriodRows]. */
interface NauSysShownRow {
    val offerId: Long
    val yachtId: Long
    val locationFromId: Long
    val locationToId: Long
}
