package hr.workspace.boat4you.domains.catalouge.publicslug

import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.catalouge.utils.YachtPublicSlug
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/**
 * Maintenance of the frozen public slugs (yacht.public_slug, V9_90; owner rule 16.10.2026). Database only, so either
 * node serves it. Both operations are idempotent and logged.
 *
 * - [backfill]: every boat without a frozen slug gets the slug it is served under today (SlugUtils on the current
 *   manufacturer, model and public name - exactly the YachtPublicSlug fallback), so no URL changes and no lastmod moves
 *   (public_slug is not in the V9_71 / V9_88 trigger). Batches of [BATCH] rows by id, each in its own short transaction;
 *   a row a sync is writing right now is skipped (SKIP LOCKED, never waited for) and left for the next run - only NULL
 *   rows are ever written, so a run never replaces a slug frozen meanwhile. A NauSys shell row (no model yet, inactive,
 *   never public) is left alone ([SHELL]): the sync freezes it when its model arrives, and a slug frozen from the
 *   name alone now would keep it on that URL for ever.
 * - [refresh]: the owner-approved exception - one boat's slug recomputed from its current name and model. When the
 *   served URL changes, yacht_content_modified moves (as the V9_71 trigger would) so the sitemap lastmod follows; the
 *   old URL keeps resolving by its trailing id and 308s to the new one.
 */
@Service
class YachtPublicSlugService(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val tx = TransactionTemplate(transactionManager)

    data class BackfillSummary(
        val dryRun: Boolean,
        /** Boats without a frozen slug before the run. */
        val missing: Int,
        /** Slugs written by this run (0 for a dry run). */
        val frozen: Int,
        /** Boats still without one after the run (skipped while a sync held them); run again. */
        val remaining: Int,
        /** NauSys shell rows without a slug, left for the sync to freeze when their model arrives - not to run again for. */
        val shells: Int,
        /** Up to [SAMPLE] boats of the run, id -> the slug frozen (dry run: that would be frozen). */
        val sample: Map<Long, String>,
        val millis: Long,
    )

    data class RefreshResult(
        val yachtId: Long,
        /** The slug the boat was served under before. */
        val previousSlug: String,
        val publicSlug: String,
        /** The served URL changed (lastmod moved); false = only frozen, or nothing to do. */
        val urlChanged: Boolean,
    )

    /** The refresh could not lock the boat within [LOCK_TIMEOUT] (a sync is writing it): nothing changed, try again. */
    class BoatBusyException : RuntimeException("yacht is being written by a sync, try again")

    private data class Row(
        val id: Long,
        val manufacturer: String?,
        val model: String?,
        val name: String?,
        val publicSlug: String?,
    ) {
        fun computed(): String = SlugUtils.toSlugWithId(manufacturer, model, name, id)

        fun served(): String = YachtPublicSlug.of(publicSlug, manufacturer, model, name, id)
    }

    private val rowMapper =
        RowMapper { rs, _ -> Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }

    fun backfill(
        dryRun: Boolean,
        actorId: Long,
    ): BackfillSummary {
        val start = System.currentTimeMillis()
        val missing = countMissing()
        val (frozen, sample) =
            if (dryRun) {
                0 to jdbcTemplate.query("$ROWS WHERE y.public_slug IS NULL AND $NOT_SHELL ORDER BY y.id LIMIT $SAMPLE", rowMapper).associate { it.id to it.computed() }
            } else {
                freezeAll()
            }
        val summary =
            BackfillSummary(dryRun, missing, frozen, if (dryRun) missing else countMissing(), countShells(), sample, System.currentTimeMillis() - start)
        log.info(
            "public slug backfill by adminUserId={} dryRun={} missing={} frozen={} remaining={} shells={} in {} ms",
            actorId,
            dryRun,
            summary.missing,
            summary.frozen,
            summary.remaining,
            summary.shells,
            summary.millis,
        )
        return summary
    }

    /** Every NULL row, batch by batch in id order: (slugs written, the first [SAMPLE] of them). */
    private fun freezeAll(): Pair<Int, Map<Long, String>> {
        val sample = linkedMapOf<Long, String>()
        var frozen = 0
        var afterId = 0L
        while (true) {
            val (rows, written) = freezeBatch(afterId)
            if (rows.isEmpty()) break
            frozen += written
            rows.take(SAMPLE - sample.size).forEach { sample[it.id] = it.computed() }
            afterId = rows.last().id
        }
        return frozen to sample
    }

    /** One short transaction: the next [BATCH] NULL rows after [afterId] no one else holds, frozen. (rows, slugs written) */
    private fun freezeBatch(afterId: Long): Pair<List<Row>, Int> =
        tx.execute {
            val rows =
                jdbcTemplate.query(
                    "$ROWS WHERE y.public_slug IS NULL AND $NOT_SHELL AND y.id > ? ORDER BY y.id LIMIT $BATCH FOR UPDATE OF y SKIP LOCKED",
                    rowMapper,
                    afterId,
                )
            if (rows.isEmpty()) return@execute rows to 0
            val written =
                jdbcTemplate.batchUpdate(
                    "UPDATE yacht SET public_slug = ? WHERE id = ? AND public_slug IS NULL",
                    rows.map { arrayOf<Any>(it.computed(), it.id) },
                )
            rows to written.sumOf { maxOf(it, 0) }
        } ?: (emptyList<Row>() to 0)

    /** Null when there is no such boat. Throws [BoatBusyException] when a sync holds the row longer than [LOCK_TIMEOUT]. */
    fun refresh(
        yachtId: Long,
        actorId: Long,
    ): RefreshResult? {
        val result =
            try {
                tx.execute {
                    jdbcTemplate.execute("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'")
                    val row = jdbcTemplate.query("$ROWS WHERE y.id = ? FOR UPDATE OF y", rowMapper, yachtId).firstOrNull() ?: return@execute null
                    val slug = row.computed()
                    val served = row.served()
                    if (row.publicSlug != slug) {
                        jdbcTemplate.update("UPDATE yacht SET public_slug = ? WHERE id = ?", slug, yachtId)
                    }
                    if (served != slug) {
                        jdbcTemplate.update(TOUCH_MODIFIED, yachtId)
                    }
                    RefreshResult(yachtId, served, slug, served != slug)
                }
            } catch (e: DataAccessException) {
                if (isLockTimeout(e)) throw BoatBusyException()
                throw e
            }
        log.info(
            "public slug refresh by adminUserId={} yachtId={} previous={} now={} urlChanged={}",
            actorId,
            yachtId,
            result?.previousSlug,
            result?.publicSlug,
            result?.urlChanged,
        )
        return result
    }

    private fun countMissing(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM yacht y WHERE y.public_slug IS NULL AND $NOT_SHELL", Int::class.java) ?: 0

    private fun countShells(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM yacht y WHERE y.public_slug IS NULL AND $SHELL", Int::class.java) ?: 0

    private fun isLockTimeout(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { (it as? SQLException)?.sqlState == "55P03" }

    companion object {
        const val BATCH = 500
        const val SAMPLE = 10
        const val LOCK_TIMEOUT = "5s"

        /** A NauSys shell row (NauSysYachtSyncService: tracked before its model, sys_active false, never public). */
        private const val SHELL = "(y.model_id IS NULL AND y.sys_active = false)"
        private const val NOT_SHELL = "NOT $SHELL"

        /** The slug inputs, joined as the boat page reads them (Yacht.model.manufacturer). */
        private const val ROWS =
            "SELECT y.id, mf.name, m.name, y.name, y.public_slug FROM yacht y " +
                "LEFT JOIN model m ON m.id = y.model_id LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id"

        /** V9_71's yacht_content_modified_touch() for one boat: the time only moves forward. */
        private const val TOUCH_MODIFIED =
            "INSERT INTO yacht_content_modified AS m (yacht_id, modified_at) VALUES (?, clock_timestamp()) " +
                "ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at WHERE m.modified_at < EXCLUDED.modified_at"
    }
}
