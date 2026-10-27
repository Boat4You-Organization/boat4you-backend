package hr.workspace.boat4you.domains.contactcenter.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The tracker's part of the security log (design 2.6, "Dnevnik"): once a minute it writes a row of `cc_security_event` for
 * - `SURGE_DROPPED`, site NULL, when the writer queue dropped rows since the last flush,
 * - `SURGE_DROPPED` per site whose visit budget refused visits ([TrackerSurgeGuard]),
 * - `BOT_BURST` per site with at least [BOT_BURST_PER_MINUTE] bot visits flagged in that minute ([LiveVisitorRegistry]).
 *
 * P1c owns the table (V9_107); this class only inserts. Until that table exists, or when the insert fails for any other reason, the
 * failure is logged as a WARN at most once an hour and the rows are lost, never retried: ingest is never affected. The flush runs on
 * every node; a node that does not take tracker traffic counts nothing and writes nothing.
 */
@Component
class TrackerSecurityLog(
    private val jdbc: JdbcTemplate,
    private val writer: TrackerWriteQueue,
    private val registry: LiveVisitorRegistry,
    private val guard: TrackerSurgeGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWarnAt = AtomicReference(Instant.EPOCH)
    private var lastDropped = 0L

    internal var clock: Clock = Clock.systemUTC()

    private data class Event(
        val type: String,
        val siteId: String?,
        val detail: String,
    )

    /** Writes the events of the last minute and returns how many rows were written. */
    @Synchronized
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun flush(): Int {
        val events = ArrayList<Event>()
        val dropped = writer.stats().dropped
        val delta = dropped - lastDropped
        if (delta > 0) {
            events += Event(SURGE_DROPPED, null, "tracker write queue full: $delta rows dropped")
            lastDropped = dropped
        }
        guard.drainRefusedVisits().toSortedMap().forEach { (siteId, n) ->
            events += Event(SURGE_DROPPED, siteId, "site visit budget: $n visits refused (429)")
        }
        registry
            .drainBotCounts()
            .toSortedMap()
            .filterValues { it >= BOT_BURST_PER_MINUTE }
            .forEach { (siteId, n) -> events += Event(BOT_BURST, siteId, "$n bot visits flagged in one minute") }
        return events.count { insert(it) }
    }

    private fun insert(event: Event): Boolean =
        runCatching {
            jdbc.update(INSERT, event.type, event.siteId, event.detail, Timestamp.from(clock.instant()))
        }.onFailure { warnHourly("cc security log: ${event.type} row not written: ${it.message}") }.isSuccess

    private fun warnHourly(message: String) {
        val now = clock.instant()
        val last = lastWarnAt.get()
        if (Duration.between(last, now) >= WARN_EVERY && lastWarnAt.compareAndSet(last, now)) log.warn(message)
    }

    companion object {
        const val BOT_BURST_PER_MINUTE = 20
        private const val SURGE_DROPPED = "SURGE_DROPPED"
        private const val BOT_BURST = "BOT_BURST"
        private const val INSERT = "INSERT INTO cc_security_event (type, site_id, detail, created_at) VALUES (?, ?, ?, ?)"
        private val WARN_EVERY: Duration = Duration.ofHours(1)
    }
}
