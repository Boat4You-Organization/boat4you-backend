package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkD3Mode
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * ROUTE_GONE for MMK (3.11.2026, `MMK_D3_MODE`; infra docs/yacht-18643-search-view-2026-10-28.md §7.2). The offer
 * upsert is keyed by (yacht, dates, product, ROUTE), so the rows of a route the partner stopped sending stay on sale
 * for ever (18643: Pula rows at the old price months after the boat moved to Propriano). After one yacht's answer is
 * upserted, a FREE row of dates and product the answer carried, but only with other routes, is that gone route.
 * Dates the answer did not carry are never judged (20.7.2026: a sweep is not a complete picture), nor dates whose
 * answer named a base we cannot map, nor a row someone is booking. A route MMK sends again is written back to its
 * partner status by the route-aware upsert.
 *
 * OFF = nothing. LOG = one INFO line per yacht with what WRITE would hide. WRITE = UNAVAILABLE + ext_status ROUTE_GONE
 * + one partner_offer_flip_log row each (job MMK_SYNC), in the yacht's write transaction.
 */
@Component
class MmkRouteGone(
    private val switches: RealPriceSwitches,
    private val jdbc: NamedParameterJdbcTemplate,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Key(
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val product: CharterType?,
    )

    /** The routes one per-yacht answer carried, per dates and product. */
    class Answer {
        private val routes = mutableMapOf<Key, MutableSet<Pair<Long, Long>>>()
        private val unmapped = mutableSetOf<Key>()

        fun add(
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
            product: CharterType?,
            fromId: Long?,
            toId: Long?,
        ) {
            if (dateFrom == null || dateTo == null) return
            val key = Key(dateFrom, dateTo, product)
            if (fromId == null || toId == null) unmapped += key else routes.getOrPut(key) { mutableSetOf() } += fromId to toId
        }

        /** True when the answer carried the row's dates and product, every base of them mapped, but not the row's route. */
        fun dropped(offer: Offer): Boolean {
            val sent = sentFor(offer) ?: return false
            val route = (offer.locationFrom?.id ?: return false) to (offer.locationTo?.id ?: return false)
            return route !in sent
        }

        /** The routes the answer carried for the row's dates and product; null = not judged (not carried, or unmapped). */
        fun sentFor(offer: Offer): Set<Pair<Long, Long>>? {
            val key = Key(offer.dateFrom ?: return null, offer.dateTo ?: return null, offer.product)
            return if (key in unmapped) null else routes[key]
        }
    }

    /**
     * Judges [existing] (the yacht's rows loaded before the upsert) against [answer] — only for an answer that asked MMK
     * for whole yachts (no base filter). Call it inside the yacht's write transaction, after the upsert; [source] names
     * the calling path in the log. @return the rows WRITE hid or LOG would hide.
     */
    fun apply(
        yacht: Yacht,
        existing: List<Offer>,
        answer: Answer,
        source: String,
    ): Int {
        val mode = switches.mmkD3Mode
        if (mode == MmkD3Mode.OFF) return 0
        val stale = staleRows(existing, answer)
        if (stale.isEmpty()) return 0
        val params = MapSqlParameterSource("ids", stale)
        val hit =
            when (mode) {
                MmkD3Mode.LOG -> jdbc.queryForList(CANDIDATES, params, Long::class.java)
                MmkD3Mode.WRITE -> jdbc.queryForList(FLIP, params, Long::class.java)
                MmkD3Mode.OFF -> emptyList()
            }
        if (hit.isEmpty()) return 0
        val rows = existing.filter { it.id in hit }
        // The session still holds these rows as FREE: detached, a later flush of this transaction cannot write them back.
        if (mode == MmkD3Mode.WRITE) rows.forEach { if (entityManager.contains(it)) entityManager.detach(it) }
        // One line per yacht and call, with every row and both route sets, so a LOG week can be counted per agency and
        // per row (a row comes back on every sweep window that carries its dates).
        log.info(
            "MMK ROUTE_GONE {} {}: yacht={} agency={} rows={} offers={} dropped={} sent={}",
            mode,
            source,
            yacht.id,
            yacht.agency?.id,
            hit.size,
            hit,
            rows.map { "${it.locationFrom?.id}->${it.locationTo?.id}" }.distinct(),
            rows.flatMap { answer.sentFor(it).orEmpty() }.distinct().map { "${it.first}->${it.second}" },
        )
        return hit.size
    }

    companion object {
        internal fun staleRows(
            existing: List<Offer>,
            answer: Answer,
        ): List<Long> = existing.filter { it.status == OfferStatus.FREE && answer.dropped(it) }.mapNotNull { it.id }

        // Re-read from the table: the status must still be FREE now, and a row with a reservation flow is someone's
        // checkout. MMK = external system 1.
        private const val CANDIDATE_ROWS =
            """
            SELECT o.id, o.yacht_id, o.date_from, o.date_to, o.status, o.ext_status, o.client_price
            FROM offer o
            WHERE o.id IN (:ids) AND o.status = 'FREE'
              AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
            """

        private const val CANDIDATES = "SELECT id FROM ($CANDIDATE_ROWS) c"

        private const val FLIP =
            """
            WITH cand AS ($CANDIDATE_ROWS FOR UPDATE OF o),
            upd AS (
                UPDATE offer o SET status = 'UNAVAILABLE', ext_status = 'ROUTE_GONE' FROM cand WHERE o.id = cand.id RETURNING o.id
            )
            INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, date_to,
                                                old_status, old_ext_status, old_client_price, new_status, first_empty_on, decided_on)
            SELECT NULL, '${ReverifyJobs.MMK_SYNC}', 'ROUTE_GONE', 1, cand.id, cand.yacht_id, cand.date_from, cand.date_to,
                   cand.status, cand.ext_status, cand.client_price, 'UNAVAILABLE', NULL, CURRENT_DATE
            FROM cand JOIN upd USING (id)
            RETURNING offer_id
            """
    }
}
