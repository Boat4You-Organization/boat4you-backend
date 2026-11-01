package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * The recording worker on cusma3 (P6 R1.4) over a real database and a temporary root: the spool becomes page views (a retried
 * sequence once), a page view closes on its end payload or after 15 minutes of silence into one gzip file in sequence order with
 * its facts and its visit, the purge keeps facts, and the quota flag follows the disk.
 */
class RecWorkerDbTest {
    @TempDir lateinit var root: Path

    private val jdbc = ContactCenterTestDb.ready()
    private val now = Instant.parse("2026-11-01T12:00:30Z")

    private fun worker(
        quotaBytes: Long = 1_000_000_000,
        assets: RecAssetArchiver? = null,
    ) = RecWorker(jdbc, ccTx(jdbc), root.toString(), Duration.ofMinutes(15), 60, quotaBytes, assets).also {
        it.clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun payload(
        seq: Int,
        events: String = "",
        end: Boolean = false,
        project: String = "boat4you",
        session: String = "s1",
    ) = """{"e":["0.8.73",$seq,${seq * 1000},500,"$project","u1","$session",1,0,${if (end) 1 else 0},0,"https://www.boat4you.com/boat/x"],"a":[$events]}"""

    private fun spool(
        at: Instant,
        json: String,
        site: String = "boat4you",
    ) {
        val dir = root.resolve("spool").resolve(RecSpool.MINUTE.format(at))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${at.toEpochMilli()}-$site-${UUID.randomUUID()}.json"), json)
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun pageView(): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM rec_page_view")

    private val click = """[1300,9,17,100,200,5000,6000,0,1,0,"Book now","/boat/x/book","abc.def",1,0,120,40,"BUTTON"]"""
    private val scroll = """[2100,10,5,0,850,"top-el","bottom-el",1]"""

    @Test
    fun `the spool becomes one page view, a retried sequence is stored once, the folder is emptied`() {
        val t = now.minus(Duration.ofMinutes(10))
        spool(t, payload(1))
        spool(t.plusSeconds(5), payload(2))
        spool(t.plusSeconds(9), payload(2))
        worker().runOnce() shouldBe RecWorker.RunResult(payloads = 2, duplicates = 1, dropped = 0, closed = 0)
        count("rec_page_view") shouldBe 1
        count("rec_payload") shouldBe 2
        pageView().let {
            it["payloads"] shouldBe 2
            it["status"] shouldBe "OPEN"
            it["url"] shouldBe "https://www.boat4you.com/boat/x"
            it["page_type"] shouldBe "BOAT"
        }
        Files.exists(root.resolve("spool").resolve(RecSpool.MINUTE.format(t))) shouldBe false
    }

    @Test
    fun `the end payload closes the page view - one gzip file in sequence order, its facts and its visit`() {
        val visitKey = UUID.randomUUID().toString()
        val (_, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", now, visitKey = visitKey)
        val t = now.minus(Duration.ofMinutes(5))
        spool(t.plusSeconds(3), payload(2, "$scroll,[2200,11,390,844],[2300,8,390,5200]", end = true))
        spool(t, payload(1, """$click,[1310,34,"ccv",["$visitKey"]],[1400,31,"x is not defined",12,4,"stack","https://www.boat4you.com/a.js"]"""))
        worker().runOnce().closed shouldBe 1
        val pv = pageView()
        pv["status"] shouldBe "CLOSED"
        pv["visit_id"] shouldBe visitId
        pv["viewport_w"] shouldBe 390
        pv["doc_h"] shouldBe 5200
        val file = root.resolve(pv["file_path"] as String)
        val lines = GZIPInputStream(Files.newInputStream(file)).bufferedReader().readLines()
        lines.map { it.substringAfter("\"0.8.73\",").substringBefore(",").toInt() } shouldContainExactly listOf(1, 2)
        jdbc.queryForMap("SELECT t_ms, x, y, hash, text, link, tag FROM rec_click") shouldBe
            mapOf("t_ms" to 1300, "x" to 100, "y" to 200, "hash" to "abc", "text" to "Book now", "link" to "/boat/x/book", "tag" to "BUTTON")
        jdbc.queryForMap("SELECT t_ms, y, top_el, bottom_el FROM rec_scroll") shouldBe
            mapOf("t_ms" to 2100, "y" to 850, "top_el" to "top-el", "bottom_el" to "bottom-el")
        jdbc.queryForObject("SELECT message FROM rec_error", String::class.java) shouldBe "x is not defined"
        // a payload after the close is not stored again
        spool(now.minusSeconds(120), payload(3))
        worker().runOnce().dropped shouldBe 1
        count("rec_payload") shouldBe 2
    }

    @Test
    fun `15 minutes of silence close a page view, the current minute's folder waits for the next run`() {
        spool(now.minus(Duration.ofMinutes(20)), payload(1, click))
        spool(now, payload(1, session = "s2"))
        worker().runOnce().closed shouldBe 1
        jdbc.queryForObject("SELECT status FROM rec_page_view WHERE session_id = 's1'", String::class.java) shouldBe "CLOSED"
        jdbc.queryForObject("SELECT count(*) FROM rec_page_view WHERE session_id = 's2'", Long::class.java) shouldBe 0
        Files.exists(root.resolve("spool").resolve(RecSpool.MINUTE.format(now))) shouldBe true
    }

    @Test
    fun `a payload of another project than its site is dropped`() {
        spool(now.minus(Duration.ofMinutes(5)), payload(1, project = "catamaran-greece"))
        worker().runOnce().dropped shouldBe 1
        count("rec_page_view") shouldBe 0
    }

    @Test
    fun `the purge deletes only files of closed page views older than 60 days, the facts stay`() {
        fun closed(
            days: Long,
            status: String = "CLOSED",
        ): Pair<Long, Path> {
            val rel = "boat4you/old/$days-$status.ndjson.gz"
            val file = root.resolve(rel).also { Files.createDirectories(it.parent) }
            Files.writeString(file, "x")
            val started = Timestamp.from(now.minus(Duration.ofDays(days)))
            val id =
                jdbc.queryForObject(
                    "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, started_at, last_payload_at, status, file_path) " +
                        "VALUES ('boat4you', 'boat4you', 'u', ?, 1, ?, ?, ?, ?) RETURNING id",
                    Long::class.java,
                    "s$days$status",
                    started,
                    started,
                    status,
                    rel,
                )!!
            jdbc.update("INSERT INTO rec_click (page_view_id, t_ms) VALUES (?, 1)", id)
            return id to file
        }
        val (old, oldFile) = closed(61)
        val (_, youngFile) = closed(10)
        val (_, openFile) = closed(70, "OPEN")
        worker().purge() shouldBe 1
        Files.exists(oldFile) shouldBe false
        Files.exists(youngFile) shouldBe true
        Files.exists(openFile) shouldBe true
        jdbc.queryForObject("SELECT file_purged FROM rec_page_view WHERE id = ?", Boolean::class.java, old) shouldBe true
        count("rec_click") shouldBe 3
    }

    @Test
    fun `over the quota the flag appears, back under it the flag goes away`() {
        val big = root.resolve("boat4you/2026-11-01/big.ndjson.gz").also { Files.createDirectories(it.parent) }
        Files.write(big, ByteArray(300))
        worker(quotaBytes = 200).checkQuota().shouldNotBeNull()
        Files.exists(root.resolve(RecSpool.QUOTA_FLAG)) shouldBe true
        Files.delete(big)
        worker(quotaBytes = 200).checkQuota()
        Files.exists(root.resolve(RecSpool.QUOTA_FLAG)) shouldBe false
    }

    @Test
    fun `a closed page view hands the stylesheets of its site to the asset archive`() {
        val seen = mutableListOf<Pair<String, Set<*>>>()
        val assets =
            mock(RecAssetArchiver::class.java) { inv ->
                if (inv.method.name == "archive") {
                    seen += (inv.arguments[0] as String) to (inv.arguments[1] as Set<*>)
                    0
                } else {
                    null
                }
            }
        val dom = """[100,5,"href=/_next/static/css/x1.css","href=https://evil.example/_next/static/css/e.css"]"""
        spool(now.minus(Duration.ofMinutes(10)), payload(1, dom, end = true))
        worker(assets = assets).runOnce()
        seen shouldBe listOf("boat4you" to setOf("/_next/static/css/x1.css"))
    }

    @Test
    fun `after 13 months a recorded page view and its facts go, like the other visit statistics`() {
        fun pageView(days: Long): Long {
            val at = Timestamp.from(now.minus(Duration.ofDays(days)))
            val id =
                jdbc.queryForObject(
                    "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, started_at, last_payload_at, status, file_purged) " +
                        "VALUES ('boat4you', 'boat4you', 'u', ?, 1, ?, ?, 'CLOSED', TRUE) RETURNING id",
                    Long::class.java,
                    "s$days",
                    at,
                    at,
                )!!
            jdbc.update("INSERT INTO rec_click (page_view_id, t_ms) VALUES (?, 1)", id)
            return id
        }
        pageView(400)
        val kept = pageView(300)
        worker().expireFacts() shouldBe 1
        jdbc.queryForList("SELECT id FROM rec_page_view", Long::class.java) shouldContainExactly listOf(kept)
        count("rec_click") shouldBe 1
    }
}
