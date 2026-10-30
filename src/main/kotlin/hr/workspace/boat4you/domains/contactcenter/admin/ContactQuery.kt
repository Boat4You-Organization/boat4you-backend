package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.admin.ConversationQuery.Companion.escapeLike
import hr.workspace.boat4you.domains.contactcenter.admin.ConversationQuery.Companion.instant
import hr.workspace.boat4you.domains.contactcenter.admin.ConversationQuery.Companion.tags
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service

/**
 * Read side of the admin contacts (P2): the list and the client card. A contact's visitors are the ones linked to it plus
 * the visitors of its conversations; everything about visits, pages and searches is read through them.
 */
@Service
class ContactQuery(
    private val jdbc: JdbcTemplate,
    private val conversations: ConversationQuery,
) {
    fun list(
        q: String?,
        status: String?,
        tag: String?,
        page: Int,
        size: Int,
    ): List<ContactRow> {
        val where = mutableListOf("TRUE")
        val args = mutableListOf<Any>()
        q
            ?.trim()
            ?.take(MAX_QUERY)
            ?.takeIf { it.isNotEmpty() }
            ?.let { escapeLike(it) }
            ?.let {
                where += "(c.name ILIKE ? ESCAPE '\\' OR c.email ILIKE ? ESCAPE '\\' OR c.phone ILIKE ? ESCAPE '\\')"
                args.addAll(listOf(it, it, it))
            }
        status?.let {
            where += "c.status = ?"
            args += it
        }
        tag?.let {
            where += "EXISTS (SELECT 1 FROM cc_contact_tag ft WHERE ft.contact_id = c.id AND ft.tag = ?)"
            args += it
        }
        val limit = size.coerceIn(1, MAX_PAGE)
        args += limit
        args += page.coerceIn(0, MAX_PAGE_INDEX) * limit
        return jdbc.query(
            "$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY COALESCE(cv.last_at, c.created_at) DESC, c.id DESC LIMIT ? OFFSET ?",
            ROW,
            *args.toTypedArray(),
        )
    }

    fun card(id: Long): ContactCard? {
        val head =
            jdbc
                .query(
                    "$SELECT WHERE c.id = ?",
                    { rs, n -> Triple(ROW.mapRow(rs, n)!!, rs.getString("note"), rs.getBoolean("email_verified")) },
                    id,
                ).firstOrNull() ?: return null
        val (contact, note, verified) = head
        val visitors =
            jdbc.queryForList(
                "SELECT id FROM cc_visitor WHERE contact_id = ? UNION " +
                    "SELECT visitor_id FROM ai_chat_session WHERE contact_id = ? AND visitor_id IS NOT NULL AND deleted_at IS NULL",
                Long::class.java,
                id,
                id,
            )
        val sources = if (visitors.isEmpty()) emptyList() else sources(visitors)
        return ContactCard(
            contact = contact,
            note = note,
            emailVerified = verified,
            firstSource = sources.firstOrNull(),
            lastSource = sources.lastOrNull(),
            visits = if (visitors.isEmpty()) emptyList() else visits(visitors),
            conversations = conversations.list(ConversationFilter(contactId = id, size = MAX_PAGE)),
            searches = searches(id, visitors),
            boatsViewed = if (visitors.isEmpty()) emptyList() else boats(visitors),
            possibleDuplicates = duplicates(contact),
        )
    }

    /** The first and the last human visit of these visitors, in that order (one row when there was a single visit). */
    private fun sources(visitors: List<Long>): List<SourceView> =
        jdbc
            .query(
                "(SELECT * FROM cc_visit WHERE visitor_id IN (${marks(visitors)}) AND NOT is_bot ORDER BY started_at, id LIMIT 1) UNION ALL " +
                    "(SELECT * FROM cc_visit WHERE visitor_id IN (${marks(visitors)}) AND NOT is_bot ORDER BY started_at DESC, id DESC LIMIT 1)",
                { rs, _ ->
                    SourceView(
                        channel = rs.getString("channel"),
                        siteId = rs.getString("site_id"),
                        referrer = rs.getString("referrer"),
                        utmSource = rs.getString("utm_source"),
                        utmMedium = rs.getString("utm_medium"),
                        utmCampaign = rs.getString("utm_campaign"),
                        fromSiteId = rs.getString("from_site_id"),
                        landingUrl = rs.getString("landing_url"),
                        at = rs.instant("started_at")!!,
                    ) to rs.getLong("id")
                },
                *(visitors + visitors).toTypedArray(),
            ).distinctBy { it.second }
            .map { it.first }

    private fun visits(visitors: List<Long>): List<VisitItem> =
        jdbc.query(
            "SELECT id, site_id, started_at, page_count, channel, country_code FROM cc_visit WHERE visitor_id IN (${marks(visitors)}) " +
                "AND NOT is_bot ORDER BY started_at DESC, id DESC LIMIT $MAX_VISITS",
            { rs, _ ->
                VisitItem(
                    rs.getLong("id"),
                    rs.getString("site_id"),
                    rs.instant("started_at")!!,
                    rs.getInt("page_count"),
                    rs.getString("channel"),
                    rs.getString("country_code"),
                )
            },
            *visitors.toTypedArray(),
        )

    /** Searches of the contact's conversations plus its visitors' site searches; estimated and flagged rows are left out. */
    private fun searches(
        contactId: Long,
        visitors: List<Long>,
    ): List<SearchItem> {
        val visitorPart =
            if (visitors.isEmpty()) {
                ""
            } else {
                " OR (cs.source = 'SITE_SEARCH' AND cs.visit_id IN (SELECT id FROM cc_visit WHERE visitor_id IN (${marks(visitors)})))"
            }
        return jdbc.query(
            "SELECT source, site_id, location, country_code, start_date, end_date, min_persons, min_cabins, vessel_type, result_count, created_at " +
                "FROM cc_search cs WHERE NOT cs.estimated AND NOT cs.flagged AND (cs.chat_session_id IN " +
                "(SELECT id FROM ai_chat_session WHERE contact_id = ? AND deleted_at IS NULL)$visitorPart) " +
                "ORDER BY cs.created_at DESC, cs.id DESC LIMIT $MAX_SEARCHES",
            ConversationQuery.SEARCH_ROW,
            *(listOf<Any>(contactId) + visitors).toTypedArray(),
        )
    }

    /** Distinct boats the visitors opened, the most recently viewed first. */
    private fun boats(visitors: List<Long>): List<String> =
        jdbc.queryForList(
            "SELECT pv.boat_slug FROM cc_page_view pv JOIN cc_visit v ON v.id = pv.visit_id WHERE v.visitor_id IN (${marks(visitors)}) " +
                "AND pv.page_type = 'BOAT' AND pv.boat_slug IS NOT NULL GROUP BY pv.boat_slug ORDER BY max(pv.viewed_at) DESC LIMIT $MAX_BOATS",
            String::class.java,
            *visitors.toTypedArray(),
        )

    /** Other contacts with the same email (any case) or the same phone digits: a suggestion only, never merged on its own. */
    private fun duplicates(contact: ContactRow): List<ContactRow> {
        val email = contact.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val digits = contact.phone?.filter { it.isDigit() }?.takeIf { it.length >= MIN_PHONE_DIGITS }
        if (email == null && digits == null) return emptyList()
        val match = mutableListOf<String>()
        val args = mutableListOf<Any>()
        email?.let {
            match += "lower(c.email) = ?"
            args += it
        }
        digits?.let {
            match += "regexp_replace(c.phone, '[^0-9]', '', 'g') = ?"
            args += it
        }
        args += contact.id
        return jdbc.query(
            "$SELECT WHERE (${match.joinToString(" OR ")}) AND c.id <> ? ORDER BY c.id LIMIT $MAX_DUPLICATES",
            ROW,
            *args.toTypedArray(),
        )
    }

    private fun marks(ids: List<Long>) = ids.joinToString(",") { "?" }

    companion object {
        private const val MAX_PAGE = 200
        private const val MAX_PAGE_INDEX = 10_000
        private const val MAX_QUERY = 100
        private const val MAX_VISITS = 100
        private const val MAX_SEARCHES = 100
        private const val MAX_BOATS = 30
        private const val MAX_DUPLICATES = 20
        private const val MIN_PHONE_DIGITS = 6

        private const val SELECT =
            "SELECT c.id, c.name, c.email, c.phone, c.status, c.note, c.email_verified, c.created_at, tg.tags, cv.n, cv.last_at " +
                "FROM cc_contact c " +
                "LEFT JOIN LATERAL (SELECT array_agg(t.tag ORDER BY t.tag) AS tags FROM cc_contact_tag t WHERE t.contact_id = c.id) tg ON TRUE " +
                "LEFT JOIN LATERAL (SELECT count(*) AS n, max(s.last_activity_at) AS last_at FROM ai_chat_session s " +
                "WHERE s.contact_id = c.id AND s.deleted_at IS NULL) cv ON TRUE"

        private val ROW =
            RowMapper { rs, _ ->
                ContactRow(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    email = rs.getString("email"),
                    phone = rs.getString("phone"),
                    status = rs.getString("status"),
                    tags = rs.tags(),
                    conversations = rs.getInt("n"),
                    lastActivityAt = rs.instant("last_at"),
                    createdAt = rs.instant("created_at")!!,
                )
            }
    }
}
