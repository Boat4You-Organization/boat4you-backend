package hr.workspace.boat4you.domains.contactcenter.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The contact-center switches, rows of the existing `settings` table whose name starts with `cc.` (they have no
 * [hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum] constant, so the admin settings list never loads them).
 *
 * Reads come from one in-memory [Snapshot], reloaded at most once per [ttl] (10 s) by a plain JDBC `SELECT`, the same way
 * as `RealPriceSwitches`. A missing row, or a value that does not parse or is out of range, means the default. A failed
 * read keeps the previous snapshot (the defaults if there is none) and logs a WARN at most once an hour.
 */
@Component
class CcSettings(
    private val jdbc: JdbcTemplate,
    @Value("\${cc.settings.cache-ttl:PT10S}") private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Monotonic clock in nanoseconds; replaced in tests. */
    internal var ticker: () -> Long = System::nanoTime

    /**
     * The snapshot, the raw `cc.*` rows it was parsed from and the moment it was loaded, in one object so a reader never pairs a
     * snapshot (or a raw value) with another one's age.
     */
    private class Loaded(
        val snapshot: Snapshot,
        val raw: Map<String, String?>,
        val atNanos: Long,
    )

    @Volatile private var loaded: Loaded? = null

    /** Guards the reload; touched only inside the lock. */
    private val lock = Any()
    private var lastWarnNanos: Long? = null

    data class Snapshot(
        val handoffMinutes: Int = DEFAULT_HANDOFF_MINUTES,
        val backfillEnabled: Boolean = false,
        val retentionIpDays: Int = DEFAULT_RETENTION_IP_DAYS,
        val trackerEnabled: Boolean = true,
    )

    val handoffMinutes: Int get() = snapshot().handoffMinutes
    val backfillEnabled: Boolean get() = snapshot().backfillEnabled
    val retentionIpDays: Int get() = snapshot().retentionIpDays
    val trackerEnabled: Boolean get() = snapshot().trackerEnabled

    /** The current values, reloaded when the cached copy is older than the TTL. Callers that need several values to agree use one snapshot. */
    fun snapshot(): Snapshot = loadedNow().snapshot

    /** The raw value of one `cc.*` row from the same cached read as [snapshot]; null when absent or NULL. */
    fun string(key: String): String? = loadedNow().raw[key]

    /** Re-reads the rows now, ignoring the TTL; a failed read keeps the previous values, as in [snapshot]. */
    fun reload() {
        synchronized(lock) { load() }
    }

    private fun loadedNow(): Loaded {
        fresh()?.let { return it }
        synchronized(lock) {
            fresh()?.let { return it }
            return load()
        }
    }

    /** Inside [lock] only. */
    private fun load(): Loaded {
        val now = ticker()
        val next =
            try {
                val raw = loadRows()
                Loaded(parse(raw), raw, now)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Never let a settings read break a chat or a job: keep what we had, or the defaults.
                if (shouldWarn(now)) log.warn("cc.* settings: read failed, keeping the previous values", e)
                Loaded(loaded?.snapshot ?: Snapshot(), loaded?.raw ?: emptyMap(), now)
            }
        loaded = next
        return next
    }

    /**
     * The kill switch of the backfill job: sets `cc.backfill.enabled` to `false` and drops the cached snapshot, so this node
     * sees it at once (the other node within the TTL). Returns the number of rows changed (0 when the row does not exist).
     * The update commits with the caller's transaction, if there is one.
     */
    fun disableBackfill(): Int {
        val rows = jdbc.update("UPDATE settings SET value = 'false', modified = now() WHERE name = '$BACKFILL_ENABLED'")
        // Under the lock, so a reload that read the old value before the update cannot be cached after this drop.
        synchronized(lock) { loaded = null }
        return rows
    }

    /**
     * Keeps the snapshot warm so that a tracker request normally reads the cached copy and never waits on the settings query.
     * It calls [snapshot], so the TTL still applies: the row is read at most once per [ttl].
     */
    @Scheduled(fixedDelay = 5_000)
    fun refresh() {
        snapshot()
    }

    private fun fresh(): Loaded? = loaded?.takeIf { ticker() - it.atNanos < ttl.toNanos() }

    private fun loadRows(): Map<String, String?> {
        val values = HashMap<String, String?>()
        // ORDER BY id: if a key ever has two rows, the newest wins.
        for (row in jdbc.queryForList("SELECT name, value FROM settings WHERE name LIKE 'cc.%' ORDER BY id")) {
            values[row["name"].toString()] = row["value"]?.toString()
        }
        return values
    }

    private fun parse(raw: Map<String, String?>) =
        Snapshot(
            handoffMinutes = raw[HANDOFF_MINUTES]?.trim()?.toIntOrNull()?.takeIf { it in 1..120 } ?: DEFAULT_HANDOFF_MINUTES,
            backfillEnabled = raw[BACKFILL_ENABLED]?.trim().equals("true", ignoreCase = true),
            retentionIpDays = raw[RETENTION_IP_DAYS]?.trim()?.toIntOrNull()?.takeIf { it in 1..3650 } ?: DEFAULT_RETENTION_IP_DAYS,
            // On unless the row is a literal `false`: unlike backfillEnabled, a missing or garbled value keeps the tracker running.
            trackerEnabled = !raw[TRACKER_ENABLED]?.trim().equals("false", ignoreCase = true),
        )

    private fun shouldWarn(now: Long): Boolean {
        val last = lastWarnNanos
        if (last != null && now - last < WARN_INTERVAL.toNanos()) return false
        lastWarnNanos = now
        return true
    }

    companion object {
        const val HANDOFF_MINUTES = "cc.handoff.minutes"
        const val BACKFILL_ENABLED = "cc.backfill.enabled"
        const val RETENTION_IP_DAYS = "cc.retention.ip.days"
        const val TRACKER_ENABLED = "cc.tracker.enabled"

        private const val DEFAULT_HANDOFF_MINUTES = 5
        private const val DEFAULT_RETENTION_IP_DAYS = 90
        private val WARN_INTERVAL: Duration = Duration.ofHours(1)
    }
}
