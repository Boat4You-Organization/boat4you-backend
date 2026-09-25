package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.ReviewStatus
import hr.workspace.boat4you.domains.review.dto.ReviewValuesDto
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** One review request (the magic link) as stored; `dbNow` is the database clock at read time. */
data class ReviewRequestRow(
    val id: Long,
    val reservationId: Long,
    val kind: ReviewKind,
    val locale: String,
    val sentAt: Instant,
    val expiresAt: Instant,
    val dbNow: Instant,
)

/** Everything the e-mail and the form show about the reservation. */
data class ReviewReservationContext(
    val reservationId: Long,
    val reservationNumber: String?,
    val dateFrom: LocalDateTime?,
    val dateTo: LocalDateTime?,
    val yachtId: Long?,
    val yachtName: String?,
    val modelName: String?,
    val manufacturerName: String?,
    val yachtMainImageId: Long?,
    val baseName: String?,
    val baseCountry: String?,
    val flowEmail: String?,
    val flowName: String?,
    val flowSurname: String?,
    val userId: Long?,
    val userName: String?,
    val userSurname: String?,
    val userLanguage: String?,
    val userCountry: String?,
    /** users.unsubscribe_token — the same one-click opt-out (marketing_opt_out) as the birthday mail. */
    val userUnsubscribeToken: String? = null,
) {
    /** Manufacturer + Model + Name (e-mail rule), without repeating a manufacturer the model name already starts with. */
    val yachtFullLabel: String
        get() = ReviewLabels.yachtFullLabel(manufacturerName, modelName, yachtName)

    val customerFirstName: String?
        get() = (userName ?: flowName)?.trim()?.takeIf { it.isNotBlank() }

    val customerFullName: String?
        get() =
            listOf(userName ?: flowName, userSurname ?: flowSurname)
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
}

/** A stored review; `dbNow` is the database clock at read time. */
data class ReviewRow(
    val id: Long,
    val reservationId: Long,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val values: ReviewValuesDto,
    val createdAt: Instant,
    val updatedAt: Instant,
    val dbNow: Instant,
)

data class AdminReviewRow(
    val review: ReviewRow,
    val guestCountry: String?,
    val charterMonth: LocalDate?,
    val statusChangedAt: Instant?,
    val reservationNumber: String?,
    val yachtId: Long?,
    val yachtFullLabel: String?,
    val customerName: String?,
    val customerEmail: String?,
    val bookingReviewId: Long?,
)

object ReviewLabels {
    fun yachtFullLabel(
        manufacturer: String?,
        model: String?,
        name: String?,
    ): String {
        val mf = manufacturer?.trim()?.takeIf { it.isNotBlank() }
        val md = model?.trim()?.takeIf { it.isNotBlank() }
        val nm = name?.trim()?.takeIf { it.isNotBlank() }
        val manufacturerPart = mf?.takeUnless { md != null && md.startsWith(it, ignoreCase = true) }
        return listOfNotNull(manufacturerPart, md, nm).joinToString(" ").ifBlank { "—" }
    }
}

/**
 * Plain SQL for the review collection. Deliberately JDBC (no JPA entities): the eligibility queries are set-based,
 * the tables are small, and every statement here is covered against a real PostgreSQL in ReviewCollectionIntegrationTest.
 *
 * Eligibility (both kinds): a confirmed booking (sys_status RESERVATION) that is real — partner-backed
 * (external_id set) and not an admin FICTITIOUS swap row — whose customer account is not GDPR-deleted and has not
 * opted out of courtesy mail (users.marketing_opt_out, the same flag the birthday mail honours).
 */
@Repository
class ReviewDao(
    private val jdbc: JdbcTemplate,
) {
    companion object {
        /** A booking review is only asked right after the FIRST payment; older first payments never qualify. */
        const val BOOKING_FIRST_PAYMENT_MAX_AGE_HOURS = 48

        /** Yacht review: from charter end + 3 days ... */
        const val YACHT_DAYS_AFTER_CHARTER = 3

        /** ... up to charter end + 14 days (a missed run catches up; the first run does not mail old charters). */
        const val YACHT_LOOKBACK_DAYS = 14

        private const val REAL_CONFIRMED_BOOKING = """
            r.sys_status = 'RESERVATION'
            AND r.external_id IS NOT NULL
            AND (r.external_status IS NULL OR r.external_status <> 'FICTITIOUS')
            AND u.deleted_at IS NULL
            AND u.marketing_opt_out = FALSE
            AND COALESCE(rf.email, '') <> ''
        """

        private const val REVIEW_COLUMNS = """
            rr.id, rr.reservation_id, rr.kind, rr.status, rr.rating,
            rr.score_ease_of_booking, rr.score_communication, rr.score_value_transparency,
            rr.score_boat_condition, rr.score_cleanliness, rr.score_check_in_out, rr.score_charter_company, rr.score_value,
            rr.title, rr.text, rr.locale, rr.publish_consent, rr.created_at, rr.updated_at, now() AS db_now
        """

        /** Sub-score key (API) -> column. */
        val SCORE_COLUMNS: Map<String, String> =
            mapOf(
                "easeOfBooking" to "score_ease_of_booking",
                "communication" to "score_communication",
                "valueTransparency" to "score_value_transparency",
                "boatCondition" to "score_boat_condition",
                "cleanliness" to "score_cleanliness",
                "checkInOut" to "score_check_in_out",
                "charterCompany" to "score_charter_company",
                "value" to "score_value",
            )
    }

    /**
     * Reservations that should get the BOOKING review request now: first payment (earliest paid phase of the flow)
     * within the last 48 h, no BOOKING request yet — neither for this reservation nor for an earlier reservation of
     * the same yacht-swap chain (an admin replacement booking carries the paid instalment over and must not ask
     * the same customer twice).
     */
    fun bookingCandidateIds(
        limit: Int,
        reservationId: Long? = null,
    ): List<Long> {
        val onlyOne = if (reservationId != null) "AND r.id = ?" else ""
        val sql =
            """
            SELECT r.id
            FROM reservation r
            JOIN reservation_flow rf ON rf.id = r.reservation_flow_id
            JOIN users u ON u.id = rf.user_id
            WHERE $REAL_CONFIRMED_BOOKING
              AND (SELECT min(pp.paid_on) FROM reservation_payment_phase pp WHERE pp.reservation_flow_id = rf.id)
                  >= LOCALTIMESTAMP - make_interval(hours => ?)
              AND NOT EXISTS (SELECT 1 FROM review_request q WHERE q.reservation_id = r.id AND q.kind = 'BOOKING')
              AND NOT EXISTS (
                  WITH RECURSIVE ancestors(flow_id, depth) AS (
                      SELECT rf.previous_flow_id, 1 WHERE rf.previous_flow_id IS NOT NULL
                      UNION ALL
                      SELECT p.previous_flow_id, a.depth + 1
                      FROM reservation_flow p
                      JOIN ancestors a ON p.id = a.flow_id
                      WHERE p.previous_flow_id IS NOT NULL AND a.depth < 20
                  )
                  SELECT 1
                  FROM ancestors a
                  JOIN reservation ra ON ra.reservation_flow_id = a.flow_id
                  JOIN review_request qa ON qa.reservation_id = ra.id AND qa.kind = 'BOOKING'
              )
              $onlyOne
            ORDER BY r.id
            LIMIT ?
            """.trimIndent()
        val args = listOfNotNull<Any>(BOOKING_FIRST_PAYMENT_MAX_AGE_HOURS, reservationId, limit)
        return jdbc.queryForList(sql, Long::class.java, *args.toTypedArray())
    }

    /** Reservations whose charter ended 3-14 days ago, paid (at least one paid instalment), no YACHT request yet. */
    fun yachtCandidateIds(limit: Int): List<Long> =
        jdbc.queryForList(
            """
            SELECT r.id
            FROM reservation r
            JOIN reservation_flow rf ON rf.id = r.reservation_flow_id
            JOIN users u ON u.id = rf.user_id
            WHERE $REAL_CONFIRMED_BOOKING
              AND r.date_to::date <= CURRENT_DATE - ?
              AND r.date_to::date >= CURRENT_DATE - ?
              AND EXISTS (
                  SELECT 1 FROM reservation_payment_phase pp
                  WHERE pp.reservation_flow_id = rf.id AND pp.paid_on IS NOT NULL
              )
              AND NOT EXISTS (SELECT 1 FROM review_request q WHERE q.reservation_id = r.id AND q.kind = 'YACHT')
            ORDER BY r.date_to, r.id
            LIMIT ?
            """.trimIndent(),
            Long::class.java,
            YACHT_DAYS_AFTER_CHARTER,
            YACHT_LOOKBACK_DAYS,
            limit,
        )

    /**
     * The "never double-send" guard: inserts the request row unless one exists for (reservation, kind). Returns true
     * only for the caller that inserted it — that caller, and only it, sends the e-mail.
     */
    fun claimRequest(
        reservationId: Long,
        kind: ReviewKind,
        tokenHash: String,
        locale: String,
        validityDays: Long,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO review_request (reservation_id, kind, token_hash, locale, sent_at, expires_at)
            VALUES (?, ?, ?, ?, now(), now() + make_interval(days => ?))
            ON CONFLICT (reservation_id, kind) DO NOTHING
            """.trimIndent(),
            reservationId,
            kind.name,
            tokenHash,
            locale,
            validityDays.toInt(),
        ) == 1

    /**
     * Admin re-ask: a real, confirmed, paid booking of a reachable customer (the base rules, without the BOOKING
     * 48 h / YACHT 3-14 day timing — the admin decides the moment).
     */
    fun isResendEligible(reservationId: Long): Boolean =
        jdbc
            .queryForList(
                """
                SELECT r.id
                FROM reservation r
                JOIN reservation_flow rf ON rf.id = r.reservation_flow_id
                JOIN users u ON u.id = rf.user_id
                WHERE r.id = ?
                  AND $REAL_CONFIRMED_BOOKING
                  AND EXISTS (
                      SELECT 1 FROM reservation_payment_phase pp
                      WHERE pp.reservation_flow_id = rf.id AND pp.paid_on IS NOT NULL
                  )
                """.trimIndent(),
                Long::class.java,
                reservationId,
            ).isNotEmpty()

    /** Removes the (reservation, kind) request unless the customer already answered it. Returns rows deleted. */
    fun deleteUnansweredRequest(
        reservationId: Long,
        kind: ReviewKind,
    ): Int =
        jdbc.update(
            """
            DELETE FROM review_request q
            WHERE q.reservation_id = ? AND q.kind = ?
              AND NOT EXISTS (
                  SELECT 1 FROM reservation_review rr WHERE rr.reservation_id = q.reservation_id AND rr.kind = q.kind
              )
            """.trimIndent(),
            reservationId,
            kind.name,
        )

    fun findRequestByTokenHash(tokenHash: String): ReviewRequestRow? =
        jdbc
            .query(
                """
                SELECT id, reservation_id, kind, locale, sent_at, expires_at, now() AS db_now
                FROM review_request
                WHERE token_hash = ?
                """.trimIndent(),
                { rs, _ ->
                    ReviewRequestRow(
                        id = rs.getLong("id"),
                        reservationId = rs.getLong("reservation_id"),
                        kind = ReviewKind.valueOf(rs.getString("kind")),
                        locale = rs.getString("locale"),
                        sentAt = rs.instant("sent_at")!!,
                        expiresAt = rs.instant("expires_at")!!,
                        dbNow = rs.instant("db_now")!!,
                    )
                },
                tokenHash,
            ).firstOrNull()

    fun loadReservationContext(reservationId: Long): ReviewReservationContext? =
        jdbc
            .query(
                """
                SELECT r.id, r.reservation_number, r.date_from, r.date_to,
                       y.id AS yacht_id, y.name AS yacht_name, y.main_image_id,
                       m.name AS model_name, mf.name AS manufacturer_name,
                       l.name AS base_name, c.name AS base_country,
                       rf.email AS flow_email, rf.name AS flow_name, rf.surname AS flow_surname,
                       u.id AS user_id, u.name AS user_name, u.surname AS user_surname,
                       u.language AS user_language, u.country AS user_country,
                       u.unsubscribe_token AS user_unsubscribe_token
                FROM reservation r
                JOIN reservation_flow rf ON rf.id = r.reservation_flow_id
                LEFT JOIN users u ON u.id = rf.user_id
                LEFT JOIN yacht y ON y.id = rf.yacht_id
                LEFT JOIN model m ON m.id = y.model_id
                LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
                LEFT JOIN location l ON l.id = r.location_from
                LEFT JOIN country c ON c.id = l.country_id
                WHERE r.id = ?
                """.trimIndent(),
                { rs, _ ->
                    ReviewReservationContext(
                        reservationId = rs.getLong("id"),
                        reservationNumber = rs.getString("reservation_number"),
                        dateFrom = rs.getObject("date_from", LocalDateTime::class.java),
                        dateTo = rs.getObject("date_to", LocalDateTime::class.java),
                        yachtId = rs.longOrNull("yacht_id"),
                        yachtName = rs.getString("yacht_name"),
                        modelName = rs.getString("model_name"),
                        manufacturerName = rs.getString("manufacturer_name"),
                        yachtMainImageId = rs.longOrNull("main_image_id"),
                        baseName = rs.getString("base_name"),
                        baseCountry = rs.getString("base_country"),
                        flowEmail = rs.getString("flow_email"),
                        flowName = rs.getString("flow_name"),
                        flowSurname = rs.getString("flow_surname"),
                        userId = rs.longOrNull("user_id"),
                        userName = rs.getString("user_name"),
                        userSurname = rs.getString("user_surname"),
                        userLanguage = rs.getString("user_language"),
                        userCountry = rs.getString("user_country"),
                        userUnsubscribeToken = rs.getString("user_unsubscribe_token"),
                    )
                },
                reservationId,
            ).firstOrNull()

    fun findReview(
        reservationId: Long,
        kind: ReviewKind,
    ): ReviewRow? =
        jdbc
            .query(
                "SELECT $REVIEW_COLUMNS FROM reservation_review rr WHERE rr.reservation_id = ? AND rr.kind = ?",
                { rs, _ -> rs.toReviewRow() },
                reservationId,
                kind.name,
            ).firstOrNull()

    fun findReviewById(id: Long): ReviewRow? =
        jdbc
            .query("SELECT $REVIEW_COLUMNS FROM reservation_review rr WHERE rr.id = ?", { rs, _ -> rs.toReviewRow() }, id)
            .firstOrNull()

    /**
     * Inserts the review and links BOOKING <-> YACHT of the same reservation (a yacht review points at the booking
     * review whichever is written first). Throws DuplicateKeyException when a concurrent submit won the
     * (reservation, kind) unique key; the caller treats that as an edit.
     */
    fun insertReview(
        request: ReviewRequestRow,
        context: ReviewReservationContext,
        values: ReviewValuesDto,
    ): Long {
        val scoreColumns = SCORE_COLUMNS.values.joinToString(", ")
        val scorePlaceholders = SCORE_COLUMNS.values.joinToString(", ") { "?" }
        val scoreArgs = SCORE_COLUMNS.keys.map { values.scores[it] }
        val charterMonth = context.dateFrom?.toLocalDate()?.withDayOfMonth(1)
        val id =
            jdbc.queryForObject(
                """
                INSERT INTO reservation_review (
                    reservation_id, request_id, kind, yacht_id, user_id, booking_review_id, rating, $scoreColumns,
                    title, text, locale, guest_country, charter_month, publish_consent
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'YACHT' THEN
                        (SELECT b.id FROM reservation_review b WHERE b.reservation_id = ? AND b.kind = 'BOOKING')
                    END,
                    ?, $scorePlaceholders, ?, ?, ?, ?, ?, ?
                )
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                request.reservationId,
                request.id,
                request.kind.name,
                context.yachtId,
                context.userId,
                request.kind.name,
                request.reservationId,
                values.rating,
                *scoreArgs.toTypedArray(),
                values.title,
                values.text,
                values.locale,
                context.userCountry?.trim()?.take(100)?.takeIf { it.isNotBlank() },
                charterMonth,
                values.publishConsent,
            )!!
        if (request.kind == ReviewKind.BOOKING) {
            jdbc.update(
                """
                UPDATE reservation_review SET booking_review_id = ?
                WHERE reservation_id = ? AND kind = 'YACHT' AND booking_review_id IS NULL
                """.trimIndent(),
                id,
                request.reservationId,
            )
        }
        return id
    }

    /**
     * Customer edit; the 24 h window is enforced here too, so a request at the boundary cannot slip through.
     * Every edit goes back to moderation: an already PUBLISHED (or HIDDEN) review returns to NEW with the moderation
     * stamp cleared, so replaced text or a withdrawn publish consent can never stay published without an admin.
     */
    fun updateReviewWithinEditWindow(
        id: Long,
        values: ReviewValuesDto,
        editWindowHours: Long,
    ): Boolean {
        val scoreSets = SCORE_COLUMNS.values.joinToString(", ") { "$it = ?" }
        val scoreArgs = SCORE_COLUMNS.keys.map { values.scores[it] }
        return jdbc.update(
            """
            UPDATE reservation_review
            SET rating = ?, $scoreSets, title = ?, text = ?, locale = ?, publish_consent = ?, updated_at = now(),
                status = 'NEW',
                status_changed_at = CASE WHEN status = 'NEW' THEN status_changed_at END,
                status_changed_by_user_id = CASE WHEN status = 'NEW' THEN status_changed_by_user_id END
            WHERE id = ? AND created_at > now() - make_interval(hours => ?)
            """.trimIndent(),
            values.rating,
            *scoreArgs.toTypedArray(),
            values.title,
            values.text,
            values.locale,
            values.publishConsent,
            id,
            editWindowHours.toInt(),
        ) == 1
    }

    fun countForAdmin(
        kind: ReviewKind?,
        status: ReviewStatus?,
    ): Long {
        val (where, args) = adminFilter(kind, status, null)
        return jdbc.queryForObject("SELECT count(*) FROM reservation_review rr $where", Long::class.java, *args)!!
    }

    fun findAdminRowById(id: Long): AdminReviewRow? = listForAdmin(null, null, 1, 0, id).firstOrNull()

    fun listForAdmin(
        kind: ReviewKind?,
        status: ReviewStatus?,
        limit: Int,
        offset: Long,
        id: Long? = null,
    ): List<AdminReviewRow> {
        val (where, args) = adminFilter(kind, status, id)
        return jdbc.query(
            """
            SELECT $REVIEW_COLUMNS, rr.guest_country, rr.charter_month, rr.status_changed_at, rr.booking_review_id,
                   r.reservation_number, rr.yacht_id, y.name AS yacht_name, m.name AS model_name,
                   mf.name AS manufacturer_name, rf.name AS flow_name, rf.surname AS flow_surname, rf.email AS flow_email
            FROM reservation_review rr
            JOIN reservation r ON r.id = rr.reservation_id
            JOIN reservation_flow rf ON rf.id = r.reservation_flow_id
            LEFT JOIN yacht y ON y.id = rr.yacht_id
            LEFT JOIN model m ON m.id = y.model_id
            LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            $where
            ORDER BY rr.created_at DESC, rr.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs, _ ->
                AdminReviewRow(
                    review = rs.toReviewRow(),
                    guestCountry = rs.getString("guest_country"),
                    charterMonth = rs.getObject("charter_month", LocalDate::class.java),
                    statusChangedAt = rs.instant("status_changed_at"),
                    reservationNumber = rs.getString("reservation_number"),
                    yachtId = rs.longOrNull("yacht_id"),
                    // yacht_id has no FK (V9_62): a yacht deleted since then simply has no label.
                    yachtFullLabel =
                        rs.getString("yacht_name")?.let {
                            ReviewLabels.yachtFullLabel(rs.getString("manufacturer_name"), rs.getString("model_name"), it)
                        },
                    customerName =
                        listOf(rs.getString("flow_name"), rs.getString("flow_surname"))
                            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                            .joinToString(" ")
                            .takeIf { it.isNotBlank() },
                    customerEmail = rs.getString("flow_email"),
                    bookingReviewId = rs.longOrNull("booking_review_id"),
                )
            },
            *args,
            limit,
            offset,
        )
    }

    fun updateStatus(
        id: Long,
        status: ReviewStatus,
        adminUserId: Long?,
    ): Boolean =
        jdbc.update(
            """
            UPDATE reservation_review
            SET status = ?, status_changed_at = now(), status_changed_by_user_id = ?
            WHERE id = ?
            """.trimIndent(),
            status.name,
            adminUserId,
            id,
        ) == 1

    /**
     * GDPR Art. 17 (UserMutationService.softDeleteForGdpr): the customer's reviews lose their free text, country and
     * publish consent and are hidden; the anonymous ratings stay for the statistics. Returns the rows touched.
     */
    fun anonymiseForUser(userId: Long): Int =
        jdbc.update(
            """
            UPDATE reservation_review
            SET title = NULL, text = NULL, guest_country = NULL, publish_consent = FALSE,
                status = 'HIDDEN', status_changed_at = now(), updated_at = now()
            WHERE user_id = ?
            """.trimIndent(),
            userId,
        )

    private fun adminFilter(
        kind: ReviewKind?,
        status: ReviewStatus?,
        id: Long?,
    ): Pair<String, Array<Any>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        id?.let {
            clauses += "rr.id = ?"
            args += it
        }
        kind?.let {
            clauses += "rr.kind = ?"
            args += it.name
        }
        status?.let {
            clauses += "rr.status = ?"
            args += it.name
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to args.toTypedArray()
    }

    private fun ResultSet.toReviewRow(): ReviewRow {
        val kind = ReviewKind.valueOf(getString("kind"))
        val scores =
            kind.scoreKeys
                .mapNotNull { key -> intOrNull(SCORE_COLUMNS.getValue(key))?.let { key to it } }
                .toMap(LinkedHashMap())
        return ReviewRow(
            id = getLong("id"),
            reservationId = getLong("reservation_id"),
            kind = kind,
            status = ReviewStatus.valueOf(getString("status")),
            values =
                ReviewValuesDto(
                    rating = getInt("rating"),
                    scores = scores,
                    title = getString("title"),
                    text = getString("text"),
                    publishConsent = getBoolean("publish_consent"),
                    locale = getString("locale"),
                ),
            createdAt = instant("created_at")!!,
            updatedAt = instant("updated_at")!!,
            dbNow = instant("db_now")!!,
        )
    }

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
}
