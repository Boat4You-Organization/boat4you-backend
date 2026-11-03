package hr.workspace.boat4you.domains.contactcenter.rec

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.service.PageTypeClassifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The recording worker on the scheduler node, cusma3 (P6 R1.4). [root] is cusma3's /mnt/data/rec, the folder cusma2 writes
 * through NFS as /mnt/shared/rec.
 *
 * [runOnce]: every spool file of a finished minute becomes a line of its page view's staging file (`staging/<id>.ndjson`) and a
 * `rec_payload` row; a retried sequence is counted once (a line written twice is dropped at the close). A page view closes on its
 * end payload or after [silence]: its lines, one per sequence and in sequence order, become `<site>/<yyyy-mm-dd>/<session>-<page>-<id>.ndjson.gz`,
 * its facts are stored and its `ccv` variable links it to the visit. A payload whose project is not its file's site, or that comes
 * after its page view closed, is dropped. [purge] deletes the files of closed page views older than [retentionDays] (the facts
 * stay); [checkQuota] keeps `QUOTA_FULL` in step with the disk, so the ingest answers 507 above [quotaBytes].
 *
 * The spool is written by anyone who can post to the public ingest, so no single file or page view may stop the worker: a spool
 * file that throws is set aside in `spool-bad/`, a page view whose close throws becomes `FAILED`, times are clamped to their
 * columns, a gzip expands to at most [MAX_TEXT_BYTES] and a staging file grows to at most [stagingMaxBytes]. [sweep] deletes
 * what would otherwise outlive the retention.
 */
@Component
@Suppress("TooManyFunctions")
class RecWorker(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    @Value("\${cc.rec.worker-root-dir:/mnt/data/rec}") root: String,
    @Value("\${cc.rec.silence:PT15M}") private val silence: Duration,
    @Value("\${cc.rec.retention-days:60}") private val retentionDays: Long,
    @Value("\${cc.rec.quota-bytes:48318382080}") private val quotaBytes: Long,
    private val assets: RecAssetArchiver? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val mapper = ObjectMapper()
    val root: Path = Path.of(root)

    internal var clock: Clock = Clock.systemUTC()

    /** A page view whose staging file reached this many bytes takes no more payloads (the close reads the file whole). */
    internal var stagingMaxBytes: Long = STAGING_MAX_BYTES

    data class RunResult(
        val payloads: Int,
        val duplicates: Int,
        val dropped: Int,
        val closed: Int,
    )

    private class Counts {
        var payloads = 0
        var duplicates = 0
        var dropped = 0
    }

    @Suppress("TooGenericExceptionCaught") // one bad file or page view must never stop the others
    fun runOnce(): RunResult {
        val now = clock.instant()
        val counts = Counts()
        val ended = LinkedHashSet<Long>()
        val stylesheets = HashMap<String, MutableSet<String>>()
        for (dir in finishedMinutes(now)) {
            for (file in payloadFiles(dir)) {
                try {
                    ingest(file, counts)?.let(ended::add)
                    Files.deleteIfExists(file)
                } catch (e: Exception) {
                    counts.dropped++
                    log.warn("rec: spool file {} set aside: {}", file.fileName, e.toString())
                    setAside(file)
                }
            }
            runCatching { Files.deleteIfExists(dir) } // still holds a .tmp of a late write: kept for the next run
        }
        val due =
            ended +
                jdbc.queryForList(
                    "SELECT id FROM rec_page_view WHERE status = 'OPEN' AND last_payload_at < ?",
                    Long::class.java,
                    Timestamp.from(now.minus(silence)),
                )
        val closed = due.count { id -> closeOrFail(id, stylesheets) }
        // The CSS and fonts the closed recordings use, once per site and run (a site that does not answer never stops the closes).
        stylesheets.forEach { (site, paths) ->
            runCatching { assets?.archive(site, paths) }.onFailure { log.warn("rec assets of {} not archived: {}", site, it.toString()) }
        }
        return RunResult(counts.payloads, counts.duplicates, counts.dropped, closed)
    }

    /** Spool minute folders at least two minutes old (cusma2's clock may lag), oldest first. */
    private fun finishedMinutes(now: Instant): List<Path> {
        val spool = root.resolve(RecSpool.SPOOL)
        if (!Files.isDirectory(spool)) return emptyList()
        val last = RecSpool.MINUTE.format(now.minus(SPOOL_GRACE))
        return Files.list(spool).use { s -> s.filter { Files.isDirectory(it) && it.fileName.toString() < last }.sorted().toList() }
    }

    private fun payloadFiles(dir: Path): List<Path> {
        Files.list(dir).use { s ->
            return s.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }.sorted().toList()
        }
    }

    private fun setAside(file: Path) {
        runCatching {
            val bad = root.resolve(SPOOL_BAD)
            Files.createDirectories(bad)
            Files.move(file, bad.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { runCatching { Files.deleteIfExists(file) } }
    }

    /** Stores one spool file; the page view id when it was its end payload. */
    @Suppress("ReturnCount")
    private fun ingest(
        file: Path,
        counts: Counts,
    ): Long? {
        val name = file.fileName.toString()
        val gzip = name.endsWith(".gz")
        val base = name.removeSuffix(".gz").removeSuffix(".json")
        val receivedAt = base.substringBefore('-').toLongOrNull()?.let(Instant::ofEpochMilli)
        val site = if (base.length > UUID_LENGTH + 1) base.substring(base.indexOf('-') + 1, base.length - UUID_LENGTH - 1) else ""
        val text =
            try {
                val bytes = if (gzip) GZIPInputStream(Files.newInputStream(file)).use { it.readNBytes(MAX_TEXT_BYTES + 1) } else Files.readAllBytes(file)
                if (bytes.size > MAX_TEXT_BYTES) null else String(bytes, Charsets.UTF_8)
            } catch (e: IOException) {
                log.warn("rec: unreadable spool file {}: {}", name, e.toString())
                null
            }
        val envelope = text?.let { runCatching { RecExtractor.envelopeOf(it) }.getOrNull() }
        if (text == null || envelope == null || receivedAt == null || envelope.projectId != site || envelope.sessionId.isEmpty()) {
            counts.dropped++
            return null
        }
        val line = text.replace("\n", "")
        val elapsed = envelope.start.coerceIn(0, MAX_ELAPSED_MS) + envelope.duration.coerceIn(0, MAX_ELAPSED_MS)
        val (id, status) = upsert(site, envelope, receivedAt, elapsed)
        if (status != STATUS_OPEN) {
            counts.dropped++
            return null
        }
        // The line first, then the row: a crash in between repeats the line (dropped at the close), never loses it.
        val staging = staging(id)
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (Files.exists(staging) && Files.size(staging) + bytes.size > stagingMaxBytes) {
            counts.dropped++
            return if (envelope.end) id else null
        }
        Files.createDirectories(staging.parent)
        Files.write(staging, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val fresh = jdbc.update("INSERT INTO rec_payload (page_view_id, sequence) VALUES (?, ?) ON CONFLICT DO NOTHING", id, envelope.sequence) == 1
        if (!fresh) {
            counts.duplicates++
            return if (envelope.end) id else null
        }
        counts.payloads++
        jdbc.update(
            "UPDATE rec_page_view SET payloads = payloads + 1, bytes = bytes + ?, duration_ms = GREATEST(COALESCE(duration_ms, 0), ?) WHERE id = ?",
            line.length.toLong(),
            elapsed.toInt(),
            id,
        )
        return if (envelope.end) id else null
    }

    private fun upsert(
        site: String,
        e: RecExtractor.Envelope,
        receivedAt: Instant,
        elapsed: Long,
    ): Pair<Long, String> {
        val url = e.url?.take(URL_MAX)
        val pageType = url?.let { u -> runCatching { URI(u) }.getOrNull()?.let { PageTypeClassifier.classify(site, it.rawPath ?: "/").type } }
        val startedAt = Timestamp.from(receivedAt.minusMillis(elapsed))
        return jdbc.queryForObject(
            "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, url, page_type, started_at, last_payload_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (project_id, user_id, session_id, page_num) DO UPDATE SET " +
                "last_payload_at = GREATEST(rec_page_view.last_payload_at, EXCLUDED.last_payload_at), " +
                "started_at = LEAST(rec_page_view.started_at, EXCLUDED.started_at), url = COALESCE(rec_page_view.url, EXCLUDED.url), " +
                "page_type = COALESCE(rec_page_view.page_type, EXCLUDED.page_type) RETURNING id, status",
            { rs, _ -> rs.getLong("id") to rs.getString("status") },
            site,
            e.projectId,
            e.userId.take(ID_MAX),
            e.sessionId.take(ID_MAX),
            e.pageNum,
            url,
            pageType,
            startedAt,
            Timestamp.from(receivedAt),
        )!!
    }

    private fun staging(id: Long): Path = root.resolve("staging").resolve("$id.ndjson")

    /** [close], and when it throws: the page view becomes FAILED and its staging goes (it never closes, so nothing else would). */
    @Suppress("TooGenericExceptionCaught")
    private fun closeOrFail(
        id: Long,
        stylesheets: MutableMap<String, MutableSet<String>>,
    ): Boolean =
        try {
            close(id, stylesheets)
        } catch (e: Exception) {
            log.warn("rec: page view {} could not be closed, marked FAILED: {}", id, e.toString())
            runCatching { jdbc.update("UPDATE rec_page_view SET status = 'FAILED' WHERE id = ? AND status = 'OPEN'", id) }
            runCatching { Files.deleteIfExists(staging(id)) }
            false
        }

    /** Closes one open page view; false when it is no longer open or has nothing staged. */
    @Suppress("ReturnCount")
    private fun close(
        id: Long,
        stylesheets: MutableMap<String, MutableSet<String>>,
    ): Boolean {
        val row =
            jdbc
                .query(
                    "SELECT site_id, session_id, page_num, started_at FROM rec_page_view WHERE id = ? AND status = 'OPEN'",
                    { rs, _ -> Triple(rs.getString("site_id"), "${rs.getString("session_id")}-${rs.getInt("page_num")}", rs.getTimestamp("started_at").toInstant()) },
                    id,
                ).firstOrNull() ?: return false
        val (site, name, startedAt) = row
        val staging = staging(id)
        val payloads =
            (if (Files.exists(staging)) Files.readAllLines(staging) else emptyList())
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { RecExtractor.envelopeOf(line).sequence to line }.getOrNull() }
                .distinctBy { it.first }
                .sortedBy { it.first }
                .map { it.second }
        val rel = "$site/${DAY.format(startedAt)}/$name-$id.ndjson.gz"
        val file = root.resolve(rel)
        Files.createDirectories(file.parent)
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter(Charsets.UTF_8).use { w ->
            payloads.forEach {
                w.write(it)
                w.write("\n")
            }
        }
        val host = SiteIds.HOSTS.entries.firstOrNull { it.value == site }?.let { "www.${it.key}" }.orEmpty()
        payloads.forEach { stylesheets.getOrPut(site) { HashSet() } += RecAssetArchiver.stylesheets(it, host) }
        val facts = Facts()
        payloads.forEach { line ->
            runCatching { RecExtractor.parse(line) }
                .onSuccess(facts::add)
                .onFailure { log.warn("rec: page view {} has a payload the extractor refused: {}", id, it.toString()) }
        }
        try {
            tx.executeWithoutResult { store(id, rel, facts) }
        } catch (e: RuntimeException) {
            Files.deleteIfExists(file) // no row points at it, so the purge would never find it
            throw e
        }
        Files.deleteIfExists(staging)
        return true
    }

    /** What one page view's payloads say, in sequence order. */
    private class Facts {
        val clicks = ArrayList<RecExtractor.Event>()
        val scrolls = ArrayList<RecExtractor.Event>()
        val errors = ArrayList<RecExtractor.Event>()
        var navigation: Map<String, Any?>? = null
        var viewport: Pair<Int?, Int?>? = null
        var document: Pair<Int?, Int?>? = null
        var visitKey: String? = null
        val mutations = ArrayList<Long>()
        var endMs = 0L

        fun add(p: RecExtractor.Payload) {
            mutations += p.mutations
            endMs = maxOf(endMs, p.envelope.start + p.envelope.duration)
            for (e in p.events) {
                when (e.type) {
                    RecExtractor.EVENT_CLICK -> clicks += e
                    RecExtractor.EVENT_SCROLL -> scrolls += e
                    RecExtractor.EVENT_SCRIPT_ERROR -> errors += e
                    RecExtractor.EVENT_NAVIGATION -> if (navigation == null) navigation = e.data
                    RecExtractor.EVENT_RESIZE -> viewport = e.int("width") to e.int("height")
                    RecExtractor.EVENT_DOCUMENT -> document = e.int("width") to e.int("height")
                }
            }
            p.variables[VISIT_VARIABLE]?.lastOrNull()?.let { visitKey = it }
        }
    }

    private fun store(
        id: Long,
        rel: String,
        f: Facts,
    ) {
        val dead = deadClicks(f)
        jdbc.batchUpdate(
            "INSERT INTO rec_click (page_view_id, t_ms, x, y, ex, ey, target, hash, hash_beta, button, reaction, context, trust, w, h, tag, text, link, dead) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            f.clicks.mapIndexed { i, c ->
                arrayOf<Any?>(
                    id,
                    c.time.ms(),
                    c.int("x"),
                    c.int("y"),
                    c.int("eX"),
                    c.int("eY"),
                    c.long("target"),
                    c.str("hash", HASH_MAX),
                    c.str("hashBeta", HASH_MAX),
                    c.int("button"),
                    c.int("reaction"),
                    c.int("context"),
                    c.int("trust"),
                    c.int("w"),
                    c.int("h"),
                    c.str("tag", TAG_MAX),
                    c.str("text", TEXT_MAX),
                    c.str("link", LINK_MAX),
                    dead[i],
                )
            },
        )
        jdbc.batchUpdate(
            "INSERT INTO rec_scroll (page_view_id, t_ms, target, x, y, top_el, bottom_el, trust) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            f.scrolls.map { s ->
                arrayOf<Any?>(id, s.time.ms(), s.long("target"), s.int("x"), s.int("y"), s.str("top", EL_MAX), s.str("bottom", EL_MAX), s.int("trust"))
            },
        )
        // The stack is not kept: it can hold URLs with tokens.
        jdbc.batchUpdate(
            "INSERT INTO rec_error (page_view_id, t_ms, message, line, col, source) VALUES (?, ?, ?, ?, ?, ?)",
            f.errors.map { e -> arrayOf<Any?>(id, e.time.ms(), e.str("message", ERROR_MAX), e.int("line"), e.int("column"), e.str("source", ERROR_MAX)) },
        )
        f.navigation?.let { jdbc.update("INSERT INTO rec_perf (page_view_id, nav) VALUES (?, CAST(? AS JSONB)) ON CONFLICT DO NOTHING", id, mapper.writeValueAsString(it)) }
        jdbc.update(
            "UPDATE rec_page_view SET status = 'CLOSED', file_path = ?, viewport_w = ?, viewport_h = ?, doc_w = ?, doc_h = ?, " +
                "visit_id = COALESCE(visit_id, (SELECT v.id FROM cc_visit v WHERE v.visit_key = ?)) WHERE id = ?",
            rel,
            f.viewport?.first,
            f.viewport?.second,
            f.document?.first,
            f.document?.second,
            f.visitKey,
            id,
        )
    }

    /** Deletes the files of closed page views older than [retentionDays]; their rows and facts stay. Returns the files deleted. */
    fun purge(): Int {
        val cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(retentionDays)))
        val old =
            jdbc.query(
                "SELECT id, file_path FROM rec_page_view WHERE status = 'CLOSED' AND NOT file_purged AND file_path IS NOT NULL AND started_at < ? " +
                    "ORDER BY id LIMIT $PURGE_BATCH",
                { rs, _ -> rs.getLong("id") to rs.getString("file_path") },
                cutoff,
            )
        old.forEach { (id, rel) ->
            val file = root.resolve(rel).normalize()
            if (file.startsWith(root)) Files.deleteIfExists(file)
            runCatching { Files.deleteIfExists(file.parent) } // the day folder, once empty
            jdbc.update("UPDATE rec_page_view SET file_purged = TRUE WHERE id = ?", id)
        }
        return old.size
    }

    /**
     * Recorded page views older than [FACTS_MONTHS] months go with their facts (ON DELETE CASCADE), as the other visit statistics
     * do (ContactCenterRetention, 13 months); their files went after [retentionDays]. Returns how many page views went.
     */
    fun expireFacts(): Int {
        val cutoff = Timestamp.from(clock.instant().atZone(ZoneOffset.UTC).minusMonths(FACTS_MONTHS).toInstant())
        return jdbc.update(
            "DELETE FROM rec_page_view WHERE id IN (SELECT id FROM rec_page_view WHERE status <> 'OPEN' AND started_at < ? LIMIT $PURGE_BATCH)",
            cutoff,
        )
    }

    /**
     * Deletes what would otherwise outlive the retention: set-aside spool files after [BAD_KEEP], `.tmp` files of writes cusma2
     * never finished after [STALE_TMP], and the staging files of page views that are no longer open (a FAILED close, a crash
     * between the close and its delete). Returns the files deleted.
     */
    fun sweep(): Int {
        val now = clock.instant()
        var deleted = deleteOlder(root.resolve(SPOOL_BAD), now.minus(BAD_KEEP)) { true }
        val spool = root.resolve(RecSpool.SPOOL)
        if (Files.isDirectory(spool)) {
            Files.list(spool).use { it.toList() }.filter(Files::isDirectory).forEach { dir ->
                deleted += deleteOlder(dir, now.minus(STALE_TMP)) { it.fileName.toString().endsWith(".tmp") }
                runCatching { Files.deleteIfExists(dir) } // only once empty
            }
        }
        val staging = root.resolve("staging")
        if (Files.isDirectory(staging)) {
            // Listed before the open ids are read: a file of a page view opened after the listing is never looked at.
            val files = Files.list(staging).use { it.toList() }
            val open = jdbc.queryForList("SELECT id FROM rec_page_view WHERE status = 'OPEN'", Long::class.java).toHashSet()
            files
                .filter { f -> f.fileName.toString().removeSuffix(".ndjson").toLongOrNull()?.let { it !in open } == true }
                .forEach { if (Files.deleteIfExists(it)) deleted++ }
        }
        return deleted
    }

    private fun deleteOlder(
        dir: Path,
        cutoff: Instant,
        which: (Path) -> Boolean,
    ): Int {
        if (!Files.isDirectory(dir)) return 0
        val old =
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && which(it) && Files.getLastModifiedTime(it).toInstant() < cutoff }.toList()
            }
        return old.count { Files.deleteIfExists(it) }
    }

    /** Bytes under [root]; `QUOTA_FULL` exists exactly while they are at or over [quotaBytes]. */
    fun checkQuota(): Long {
        val used = Files.walk(root).use { s -> s.filter(Files::isRegularFile).mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
        val flag = root.resolve(RecSpool.QUOTA_FLAG)
        if (used >= quotaBytes) Files.writeString(flag, "$used of $quotaBytes bytes\n") else Files.deleteIfExists(flag)
        return used
    }

    fun quotaBytes(): Long = quotaBytes

    /**
     * Dead clicks of page views closed before V9_115 (or whose close could not tell): read from their files while the files
     * exist, at most [limit] page views per call. A file that cannot be read leaves its clicks not dead. Returns the page views done.
     */
    @Suppress("TooGenericExceptionCaught") // one unreadable file must not stop the others
    fun backfillDead(limit: Int): Int {
        val pending =
            jdbc.query(
                "SELECT p.id, p.file_path FROM rec_page_view p WHERE p.status <> 'OPEN' AND NOT p.file_purged AND p.file_path IS NOT NULL " +
                    "AND EXISTS (SELECT 1 FROM rec_click c WHERE c.page_view_id = p.id AND c.dead IS NULL) ORDER BY p.id LIMIT $limit",
                { rs, _ -> rs.getLong(1) to rs.getString(2) },
            )
        pending.forEach { (id, rel) ->
            try {
                backfillOne(id, rel)
            } catch (e: Exception) {
                log.warn("rec: dead clicks of page view {} not read from its file: {}", id, e.toString())
                jdbc.update("UPDATE rec_click SET dead = FALSE WHERE page_view_id = ? AND dead IS NULL", id)
            }
        }
        return pending.size
    }

    private fun backfillOne(
        id: Long,
        rel: String,
    ) {
        val file = root.resolve(rel).normalize()
        require(file.startsWith(root)) { "outside the recordings folder" }
        val facts = Facts()
        GZIPInputStream(Files.newInputStream(file)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line -> runCatching { RecExtractor.parse(line) }.onSuccess(facts::add) }
        }
        val dead = deadClicks(facts)
        val ids = jdbc.queryForList("SELECT id FROM rec_click WHERE page_view_id = ? ORDER BY id", Long::class.java, id)
        if (ids.size != dead.size) {
            jdbc.update("UPDATE rec_click SET dead = FALSE WHERE page_view_id = ? AND dead IS NULL", id)
            return
        }
        jdbc.batchUpdate("UPDATE rec_click SET dead = ? WHERE id = ?", ids.zip(dead).map { (clickId, d) -> arrayOf<Any?>(d, clickId) })
    }

    /**
     * Per click of [f], in order: dead when it has no link, is not on a form field, and neither a DOM change nor the end of the page
     * view follows within [REACT_MS] (the page did not react; Clarity's own definition is the same idea).
     */
    private fun deadClicks(f: Facts): List<Boolean> {
        val mutations = f.mutations.sorted()
        return f.clicks.map { c ->
            val t = c.time ?: 0
            val link = c.str("link", LINK_MAX)?.takeIf { it.isNotBlank() }
            val tag = c.str("tag", TAG_MAX)?.lowercase()
            val reacted = mutations.any { it > t && it <= t + REACT_MS }
            link == null && tag !in FIELD_TAGS && !reacted && f.endMs - t > REACT_MS
        }
    }

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val SPOOL_BAD = "spool-bad"
        const val VISIT_VARIABLE = "ccv"
        private val SPOOL_GRACE: Duration = Duration.ofMinutes(1)
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        private const val UUID_LENGTH = 36
        private const val URL_MAX = 1000
        private const val ID_MAX = 40
        private const val HASH_MAX = 40
        private const val TAG_MAX = 40
        private const val TEXT_MAX = 200
        private const val LINK_MAX = 500
        private const val EL_MAX = 80
        private const val ERROR_MAX = 500
        private const val PURGE_BATCH = 5000
        private const val FACTS_MONTHS = 13L
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private const val STAGING_MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_ELAPSED_MS = 7L * 24 * 60 * 60 * 1000
        private val BAD_KEEP: Duration = Duration.ofDays(7)
        private val STALE_TMP: Duration = Duration.ofDays(1)
        private const val REACT_MS = 1500L
        private val FIELD_TAGS = setOf("input", "textarea", "select", "option")
    }
}

/** An event time in ms, clamped to the INTEGER column. */
private fun Long?.ms(): Int = (this ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun RecExtractor.Event.int(key: String): Int? = (data[key] as? Number)?.toInt()

private fun RecExtractor.Event.long(key: String): Long? = (data[key] as? Number)?.toLong()

private fun RecExtractor.Event.str(
    key: String,
    max: Int,
): String? = data[key]?.toString()?.take(max)
