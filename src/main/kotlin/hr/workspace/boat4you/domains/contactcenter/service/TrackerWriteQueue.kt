package hr.workspace.boat4you.domains.contactcenter.service

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One append for the tracker writer: a page view or an event of the visit [visitId] (the BIGSERIAL id, not the public key). */
sealed interface TrackerRow {
    val visitId: Long
}

data class PageViewRow(
    override val visitId: Long,
    val pv: PageViewIn,
) : TrackerRow

data class EventRow(
    override val visitId: Long,
    val siteId: String,
    val ev: EventIn,
) : TrackerRow

/** The pending `last_seen_at` of a visit and, if a bot was flagged since, its first reason. */
data class VisitTouch(
    val lastSeenAt: Instant,
    val botReason: String?,
)

/** [queued] rows now waiting, [dropped] rows and touches refused for want of room, [written] rows taken into a committed flush, [failed] rows taken into a flush that failed. */
data class WriterStats(
    val queued: Int,
    val dropped: Long,
    val written: Long,
    val failed: Long,
)

/**
 * The append-only write path of the tracker (design 2.6, 6.6): requests [offer] rows and return at once, one daemon thread writes them in
 * batches (every [FLUSH_EVERY] or [BATCH_ROWS] rows, whichever comes first) in one transaction. Nothing here ever blocks a request:
 * a full queue drops the row and counts it. A failed batch is counted and not retried (a lost beacon is acceptable, design 6.6).
 *
 * Page views and events are deduplicated by `(visit, client id)` against the last [DEDUPE_KEYS] keys, so a beat that the widget
 * retries is stored once. The keys are remembered only after the batch commits. `cc_visit.last_seen_at` (and a bot flag) is not
 * written per beat but merged per visit in [touch] and written with the next batch.
 */
@Component
class TrackerWriteQueue(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val queue = ArrayBlockingQueue<TrackerRow>(CAPACITY)
    private val touches = ConcurrentHashMap<Long, VisitTouch>()
    private val dropped = AtomicLong()
    private val written = AtomicLong()
    private val failed = AtomicLong()
    private val lastWarnAt = AtomicReference(Instant.EPOCH)

    /** `p:<visit>:<page view client id>` to the new `cc_page_view.id`, `e:<visit>:<event client id>` to 0. Only the writer thread touches it. */
    private val seen: MutableMap<String, Long> =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > DEDUPE_KEYS
        }

    @Volatile private var running = false
    private var writer: Thread? = null

    internal var clock: Clock = Clock.systemUTC()

    /** Queues [row]; false (and counted) when the queue is full. Never blocks. */
    fun offer(row: TrackerRow): Boolean {
        if (queue.offer(row)) return true
        dropped.incrementAndGet()
        warnEveryMinute("cc tracker write queue is full ($CAPACITY rows), dropping rows")
        return false
    }

    /** Remembers that [visitId] was seen at [lastSeenAt], with [botReason] if a bot was flagged; a visit that is already pending is merged. */
    fun touch(
        visitId: Long,
        lastSeenAt: Instant,
        botReason: String?,
    ) {
        if (touches.size >= MAX_TOUCHES && !touches.containsKey(visitId)) {
            dropped.incrementAndGet()
            warnEveryMinute("cc tracker has $MAX_TOUCHES visits waiting for a last_seen_at write, dropping touches")
            return
        }
        touches.merge(visitId, VisitTouch(lastSeenAt, botReason)) { a, b ->
            VisitTouch(maxOf(a.lastSeenAt, b.lastSeenAt), a.botReason ?: b.botReason)
        }
    }

    fun stats(): WriterStats = WriterStats(queue.size, dropped.get(), written.get(), failed.get())

    @PostConstruct
    fun start() {
        synchronized(this) {
            if (writer != null) return
            running = true
            writer =
                Thread({ while (running) runCatching { flushOnce(FLUSH_EVERY) } }, "cc-tracker-writer")
                    .apply { isDaemon = true }
                    .also { it.start() }
        }
    }

    /** Ends the writer thread, then writes what is left (one flush, at most [BATCH_ROWS] rows). */
    @PreDestroy
    fun stop() {
        val thread =
            synchronized(this) {
                running = false
                writer.also { writer = null }
            }
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(STOP_JOIN_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // `seen` belongs to the writer thread: if it is stuck in the database, leave its rows alone instead of racing it.
        if (thread?.isAlive != true) flushOnce(Duration.ZERO)
    }

    /**
     * Takes up to [BATCH_ROWS] rows, waiting at most [maxWait] for them, and writes them with the pending touches in one transaction.
     * Returns the number of rows taken (also when the write failed). Not for concurrent callers: the writer thread, or a test.
     */
    internal fun flushOnce(maxWait: Duration): Int {
        val batch = ArrayList<TrackerRow>(BATCH_ROWS)
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (batch.size < BATCH_ROWS) {
            val left = deadline - System.nanoTime()
            val row =
                try {
                    if (left <= 0) queue.poll() else queue.poll(left, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    // stop() interrupts the wait: still write what was taken (the writer loop ends on `running`, so the flag is not restored)
                    break
                } ?: break
            batch += row
            queue.drainTo(batch, BATCH_ROWS - batch.size)
        }
        val touched = HashMap<Long, VisitTouch>().also { m -> touches.keys.toList().forEach { k -> touches.remove(k)?.let { m[k] = it } } }
        if (batch.isEmpty() && touched.isEmpty()) return 0
        val newKeys = HashMap<String, Long>()
        try {
            tx.executeWithoutResult { write(batch, touched, newKeys) }
            seen.putAll(newKeys) // dedupe keys only after commit
            written.addAndGet(batch.size.toLong())
        } catch (e: Exception) {
            // beacon loss is acceptable (design 6.6); never retry in a loop
            failed.addAndGet(batch.size.toLong())
            warnEveryMinute("cc tracker batch of ${batch.size} rows failed: ${e.message}")
        }
        return batch.size
    }

    internal fun queuedRows(): List<TrackerRow> = queue.toList()

    internal fun pendingTouches(): Map<Long, VisitTouch> = touches.toMap()

    private fun write(
        batch: List<TrackerRow>,
        touched: Map<Long, VisitTouch>,
        newKeys: MutableMap<String, Long>,
    ) {
        val views = LinkedHashMap<String, PageViewRow>()
        val events = LinkedHashMap<String, EventRow>()
        for (row in batch) {
            when (row) {
                is PageViewRow -> "p:${row.visitId}:${row.pv.clientId}".let { if (seen[it] == null) views.putIfAbsent(it, row) }
                is EventRow -> "e:${row.visitId}:${row.ev.clientId}".let { if (seen[it] == null) events.putIfAbsent(it, row) }
            }
        }
        insertPageViews(views, newKeys)
        insertEvents(events, newKeys)
        insertSearches(events.values)
        updateTouches(touched)
    }

    private fun insertPageViews(
        views: Map<String, PageViewRow>,
        newKeys: MutableMap<String, Long>,
    ) {
        if (views.isEmpty()) return
        val ids =
            jdbc.queryForList(
                "SELECT nextval(pg_get_serial_sequence('cc_page_view', 'id')) FROM generate_series(1, ?)",
                Long::class.java,
                views.size,
            )
        val args =
            views.entries.mapIndexed { i, (key, row) ->
                newKeys[key] = ids[i]
                val pv = row.pv
                arrayOf<Any?>(ids[i], row.visitId, pv.url, pv.path, pv.title, pv.pageType, pv.boatSlug, Timestamp.from(pv.viewedAt))
            }
        jdbc.batchUpdate(
            "INSERT INTO cc_page_view (id, visit_id, url, path, title, page_type, boat_slug, viewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            args,
            intArrayOf(
                Types.BIGINT,
                Types.BIGINT,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.TIMESTAMP,
            ),
        )
        jdbc.batchUpdate(
            "UPDATE cc_visit SET page_count = page_count + ? WHERE id = ?",
            views.values.groupingBy { it.visitId }.eachCount().map { (visitId, count) -> arrayOf<Any?>(count, visitId) },
            intArrayOf(Types.INTEGER, Types.BIGINT),
        )
    }

    private fun insertEvents(
        events: Map<String, EventRow>,
        newKeys: MutableMap<String, Long>,
    ) {
        if (events.isEmpty()) return
        val args =
            events.entries.map { (key, row) ->
                newKeys[key] = 0L
                val ev = row.ev
                val pageViewKey = ev.pvClientId?.let { "p:${row.visitId}:$it" }
                val pageViewId = pageViewKey?.let { newKeys[it] ?: seen[it] }
                arrayOf<Any?>(row.visitId, pageViewId, ev.type, ev.label, ev.target, ev.payloadJson, Timestamp.from(ev.createdAt))
            }
        jdbc.batchUpdate(
            "INSERT INTO cc_event (visit_id, page_view_id, type, label, target, payload, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            args,
            intArrayOf(Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP),
        )
    }

    private fun insertSearches(events: Collection<EventRow>) {
        val args =
            events.mapNotNull { row ->
                val s = row.ev.search ?: return@mapNotNull null
                arrayOf<Any?>(
                    row.siteId,
                    row.visitId,
                    s.countryCode,
                    s.location,
                    s.did,
                    s.startDate?.let(java.sql.Date::valueOf),
                    s.endDate?.let(java.sql.Date::valueOf),
                    s.persons,
                    s.cabins,
                    s.minPriceEur,
                    s.maxPriceEur,
                    s.vesselType,
                    s.equipment,
                    s.resultCount,
                    s.totalAvailable,
                    Timestamp.from(row.ev.createdAt),
                )
            }
        if (args.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO cc_search (source, site_id, visit_id, country_code, location, did, start_date, end_date, min_persons, min_cabins, " +
                "min_price_eur, max_price_eur, vessel_type, equipment, result_count, total_available, flagged, estimated, created_at) " +
                "VALUES ('SITE_SEARCH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, ?)",
            args,
            intArrayOf(
                Types.VARCHAR,
                Types.BIGINT,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.BIGINT,
                Types.DATE,
                Types.DATE,
                Types.INTEGER,
                Types.INTEGER,
                Types.INTEGER,
                Types.INTEGER,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.INTEGER,
                Types.INTEGER,
                Types.TIMESTAMP,
            ),
        )
    }

    private fun updateTouches(touched: Map<Long, VisitTouch>) {
        if (touched.isEmpty()) return
        jdbc.batchUpdate(
            "UPDATE cc_visit SET last_seen_at = GREATEST(last_seen_at, ?), is_bot = is_bot OR ?, bot_reason = COALESCE(bot_reason, ?) WHERE id = ?",
            touched.map { (visitId, t) -> arrayOf<Any?>(Timestamp.from(t.lastSeenAt), t.botReason != null, t.botReason, visitId) },
            intArrayOf(Types.TIMESTAMP, Types.BOOLEAN, Types.VARCHAR, Types.BIGINT),
        )
    }

    /** Logs [message] unless a warning of this class went out in the last minute, so a flood of drops or failures is one line a minute. */
    private fun warnEveryMinute(message: String) {
        val now = clock.instant()
        val last = lastWarnAt.get()
        if (Duration.between(last, now) >= WARN_EVERY && lastWarnAt.compareAndSet(last, now)) log.warn(message)
    }

    companion object {
        const val CAPACITY = 5_000
        const val BATCH_ROWS = 200
        val FLUSH_EVERY: Duration = Duration.ofSeconds(2)
        const val DEDUPE_KEYS = 20_000
        const val MAX_TOUCHES = 10_000
        private const val STOP_JOIN_MILLIS = 5_000L
        private val WARN_EVERY: Duration = Duration.ofSeconds(60)
    }
}
