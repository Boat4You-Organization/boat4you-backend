package hr.workspace.boat4you.domains.contactcenter

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Shared fixtures of the tracker-ingest tests (P1b), next to [ContactCenterTestDb]. */

const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

fun ccTx(jdbc: JdbcTemplate): PlatformTransactionManager = DataSourceTransactionManager(jdbc.dataSource!!)

/**
 * Upserts the visitor and inserts one open DIRECT / DESKTOP visit of it, both stamped with [lastSeen]. Returns
 * `(visitorId, visitId)`. Instants are bound as [Timestamp] (pgjdbc rejects `Instant` in `setObject`).
 */
fun ccVisit(
    jdbc: JdbcTemplate,
    visitorKey: String,
    siteId: String,
    lastSeen: Instant,
    visitKey: String = UUID.randomUUID().toString(),
): Pair<Long, Long> {
    val at = Timestamp.from(lastSeen)
    val visitorId =
        jdbc.queryForObject(
            "INSERT INTO cc_visitor (visitor_key, first_site_id, created_at, last_seen_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (visitor_key) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at RETURNING id",
            Long::class.java,
            visitorKey,
            siteId,
            at,
            at,
        )!!
    val visitId =
        jdbc.queryForObject(
            "INSERT INTO cc_visit (visitor_id, site_id, started_at, last_seen_at, channel, device_class, visit_key) " +
                "VALUES (?, ?, ?, ?, 'DIRECT', 'DESKTOP', ?) RETURNING id",
            Long::class.java,
            visitorId,
            siteId,
            at,
            at,
            visitKey,
        )!!
    return visitorId to visitId
}

/** A hand-moved UTC clock for the time-dependent tracker classes (`internal var clock: Clock`). */
class CcTestClock(
    var now: Instant = Instant.parse("2026-10-26T10:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(d: Duration) {
        now = now.plus(d)
    }
}
