package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The player's endpoints (P6 R2): the list with validated filters, a recording's details, its raw file (gzip as stored, every
 * view audited, 503 while the share is stalled) and the archived stylesheets of a recording.
 */
class AdminRecPlayerControllerTest {
    @TempDir lateinit var root: Path

    private val filters = mutableListOf<RecFilter>()
    private val audits = mutableListOf<Long>()
    private var filePath: String? = "boat4you/2026-11-03/a.ndjson.gz"
    private val row = RecRow(5, "boat4you", "https://www.boat4you.com/", "HOME", Instant.parse("2026-11-03T10:00:00Z"), 1000, 1440, 900, "DESKTOP", 2, 0, 0, null, false, false)
    private val query =
        mock(RecQuery::class.java) { inv ->
            when (inv.method.name) {
                "list" -> filters.add(inv.arguments[0] as RecFilter).let { listOf(row) }
                "detail" -> if (inv.arguments[0] == 5L) RecDetail(row, emptyList(), emptyList(), emptyList(), emptyList()) else null
                "filePath" -> if (inv.arguments[0] == 5L) filePath else null
                "audit" -> audits.add(inv.arguments[0] as Long).let { null }
                "assets" -> mapOf("/_next/static/chunks/x.css" to "/replay-assets/www.boat4you.com/abc.css")
                else -> null
            }
        }
    private val files by lazy { RecFiles(root.toString()) }
    private val mvc by lazy {
        MockMvcBuilders
            .standaloneSetup(AdminRecController(mock(RecSettingsStore::class.java), query, files, mock(RecHeatmapQuery::class.java)))
            .setControllerAdvice(ApiErrorHandler())
            .build()
    }

    private fun admin() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mario@example.com", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `the list passes its filters on, a wrong site, device, page type or range is 400`() {
        admin()
        mvc.get("/admin/cc/rec/page-views?siteId=boat4you&device=MOBILE&pageType=BOAT&rageOnly=true&noInquiry=true&visitId=9&size=20").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(5) }
        }
        filters.single().let {
            it.siteId shouldBe "boat4you"
            it.device shouldBe "MOBILE"
            it.pageType shouldBe "BOAT"
            it.rageOnly shouldBe true
            it.noInquiry shouldBe true
            it.visitId shouldBe 9L
            it.size shouldBe 20
            (it.to.epochSecond - it.from.epochSecond) shouldBe 7L * 24 * 3600
        }
        mvc.get("/admin/cc/rec/page-views?siteId=evil").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/page-views?device=WATCH").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/page-views?pageType=X").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/page-views?from=2026-11-03T00:00:00Z&to=2026-11-01T00:00:00Z").andExpect { status { isBadRequest() } }
        filters.size shouldBe 1
    }

    @Test
    fun `a recording's details, 404 for an unknown one`() {
        admin()
        mvc.get("/admin/cc/rec/page-views/5").andExpect {
            status { isOk() }
            jsonPath("$.row.siteId") { value("boat4you") }
        }
        mvc.get("/admin/cc/rec/page-views/6").andExpect { status { isNotFound() } }
    }

    @Test
    fun `the raw recording goes out gzip as stored and the view is audited, 404 when the file is gone, 503 when the share is stalled`() {
        admin()
        Files.createDirectories(root.resolve("boat4you/2026-11-03"))
        Files.write(root.resolve("boat4you/2026-11-03/a.ndjson.gz"), byteArrayOf(0x1f, 0x8b.toByte(), 1, 2))
        mvc.get("/admin/cc/rec/page-views/5/raw").andExpect {
            status { isOk() }
            header { string("Content-Encoding", "gzip") }
            header { string("Cache-Control", "no-store") }
            content { bytes(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2)) }
        }
        audits shouldBe listOf(5L)
        mvc.get("/admin/cc/rec/page-views/6/raw").andExpect { status { isNotFound() } }
        filePath = "boat4you/2026-11-03/missing.ndjson.gz"
        mvc.get("/admin/cc/rec/page-views/5/raw").andExpect { status { isNotFound() } }
        filePath = "boat4you/2026-11-03/a.ndjson.gz"
        files.readFile = { throw RecFiles.BusyException() }
        mvc.get("/admin/cc/rec/page-views/5/raw").andExpect { status { isServiceUnavailable() } }
        audits shouldBe listOf(5L)
    }

    @Test
    fun `the archived stylesheets of a recording, 400 for an unknown site`() {
        admin()
        mvc
            .post("/admin/cc/rec/assets") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"siteId":"boat4you","paths":["/_next/static/chunks/x.css"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$['/_next/static/chunks/x.css']") { value("/replay-assets/www.boat4you.com/abc.css") }
            }
        mvc
            .post("/admin/cc/rec/assets") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"siteId":"evil","paths":[]}"""
            }.andExpect { status { isBadRequest() } }
    }
}
