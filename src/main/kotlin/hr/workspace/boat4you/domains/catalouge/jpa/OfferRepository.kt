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
