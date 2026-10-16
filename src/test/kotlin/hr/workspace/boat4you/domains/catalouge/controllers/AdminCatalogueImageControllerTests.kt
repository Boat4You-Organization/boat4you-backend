package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixDto
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixResultDto
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.services.AdminCatalogueImageService
import hr.workspace.boat4you.domains.catalouge.services.RemoteImageFetcher
import hr.workspace.boat4you.domains.catalouge.services.YachtMainImageFixService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Path
import java.util.Optional

/**
 * Marina photos admin API (design 16.10.2026) through the controller, the real service and the real FileSystemService
 * (magic bytes, 10 MB cap, WebP, `marina-{id}/` under a temp upload dir); the repositories are in-memory answers and the
 * remote download is a mock (its own rules: RemoteImageFetcherTest). Every request stores all of its photos or none.
 */
class AdminCatalogueImageControllerTests {
    @TempDir
    lateinit var uploadDir: Path

    private val rows = sortedMapOf<Long, CatalogueImage>()
    private var nextId = 2_000_000_000L
    private val repository =
        mock(CatalogueImageRepository::class.java) { inv ->
            when (inv.method.name) {
                "save", "saveAndFlush" ->
                    inv.getArgument<CatalogueImage>(0).also {
                        if (it.id == null) it.id = nextId++
                        rows[it.id!!] = it
                    }
                "findById" -> Optional.ofNullable(rows[inv.getArgument<Long>(0)])
                "findByLocationIdOrderByPositionAscIdAsc" ->
                    rows.values.filter { it.locationId == inv.getArgument<Long>(0) }.sortedWith(compareBy({ it.position }, { it.id }))
                "delete" -> rows.remove(inv.getArgument<CatalogueImage>(0).id).let { null }
                else -> null
            }
        }
    private val locations = mock(LocationRepository::class.java).also { `when`(it.existsById(63L)).thenReturn(true) }
    private val fetcher = mock(RemoteImageFetcher::class.java)
    private val mainImageFix = mock(YachtMainImageFixService::class.java)
    private val transactions =
        TransactionTemplate(
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

                override fun commit(status: TransactionStatus) = Unit

                override fun rollback(status: TransactionStatus) = Unit
            },
        )
    private lateinit var files: FileSystemService

    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32)
    private val html = "<!doctype html><html></html>".toByteArray()

    private val mvc by lazy {
        MockMvcBuilders
            .standaloneSetup(AdminCatalogueImageController(AdminCatalogueImageService(repository, locations, files, fetcher, transactions), mainImageFix))
            .setControllerAdvice(ApiErrorHandler())
            .build()
    }

    @BeforeEach
    fun setUp() {
        files = FileSystemService().also { ReflectionTestUtils.setField(it, "uploadDir", uploadDir.toString()) }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("lead@boat4you.com", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun storedFiles(): List<File> = uploadDir.toFile().walkTopDown().filter { it.isFile }.toList()

    private fun webpFile(name: String) = MockMultipartFile("files", name, "image/webp", webp)

    @Test
    fun `upload - stored as WebP under marina-{id}, credit, source and the uploader kept, positions in order`() {
        mvc
            .multipart("/admin/locations/63/images") {
                file(webpFile("kastela-1.webp"))
                file(webpFile("kastela-2.webp"))
                param("caption", "Marina Kaštela")
                param("credit", "Foto: Marina Kaštela")
                param("sourceUrl", "https://www.marinakastela.hr/galerija")
                param("licence", "PERMISSION")
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].id") { value(2_000_000_000L) }
                jsonPath("$[0].kind") { value("MARINA") }
                jsonPath("$[0].locationId") { value(63) }
                jsonPath("$[0].position") { value(0) }
                jsonPath("$[1].position") { value(1) }
                jsonPath("$[0].credit") { value("Foto: Marina Kaštela") }
                jsonPath("$[0].sourceUrl") { value("https://www.marinakastela.hr/galerija") }
                jsonPath("$[0].licence") { value("PERMISSION") }
                jsonPath("$[0].createdBy") { value("lead@boat4you.com") }
            }
        storedFiles() shouldHaveSize 2
        rows.values.forEach {
            it.url!!.startsWith("marina-63/") shouldBe true
            it.url!!.endsWith(".webp") shouldBe true
        }
        mvc.get("/admin/locations/63/images").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    fun `upload - a non-image (magic bytes) fails the whole request, the file already written is removed`() {
        mvc
            .multipart("/admin/locations/63/images") {
                file(webpFile("ok.webp"))
                file(MockMultipartFile("files", "evil.jpg", "image/jpeg", html)) // lying Content-Type
                param("credit", "Foto: Marina Kaštela")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
                jsonPath("$.message") { value("Invalid request parameters: {files[1]=Invalid file type. Only images are allowed}") }
            }
        storedFiles().shouldBeEmpty()
    }

    @Test
    fun `upload - over 10 MB, no file, no credit, a javascript source link are 400`() {
        mvc
            .multipart("/admin/locations/63/images") {
                file(MockMultipartFile("files", "big.webp", "image/webp", webp + ByteArray(10 * 1024 * 1024)))
                param("credit", "Foto: Marina Kaštela")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Invalid request parameters: {files[0]=File size exceeds maximum allowed size of 10 MB}") }
            }
        mvc
            .multipart("/admin/locations/63/images") {
                param("credit", " ")
                param("sourceUrl", "javascript:alert(1)")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
                jsonPath("$.message") {
                    value(
                        "Invalid request parameters: {credit=required (e.g. \"Foto: Marina Kaštela\"), " +
                            "sourceUrl=must be an http(s) URL of at most 1024 characters, files=at least one image required}",
                    )
                }
            }
        storedFiles().shouldBeEmpty()
    }

    @Test
    fun `an unknown location is 404`() {
        mvc.get("/admin/locations/999/images").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(4004) }
        }
        mvc
            .multipart("/admin/locations/999/images") {
                file(webpFile("a.webp"))
                param("credit", "Foto: X")
            }.andExpect { status { isNotFound() } }
        storedFiles().shouldBeEmpty()
    }

    @Test
    fun `from-url - downloaded and stored, sourceUrl defaults to the url, positions continue after the existing photos`() {
        rows[1L] =
            CatalogueImage().apply {
                id = 1L
                locationId = 63
                url = "marina-63/old.webp"
                credit = "Foto: X"
                position = 4
            }
        `when`(fetcher.fetch("https://commons.wikimedia.org/a.jpg")).thenReturn(webp)
        `when`(fetcher.fetch("https://www.marinakastela.hr/img/b.webp")).thenReturn(webp)
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"urls":[
                      {"url":"https://commons.wikimedia.org/a.jpg","credit":"Foto: Wikimedia Commons / J. Doe","licence":"CC-BY-SA-4.0",
                       "sourceUrl":"https://commons.wikimedia.org/wiki/File:A.jpg"},
                      {"url":"https://www.marinakastela.hr/img/b.webp","caption":"Marina Kaštela","credit":"Foto: Marina Kaštela"}]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].position") { value(5) }
                jsonPath("$[0].sourceUrl") { value("https://commons.wikimedia.org/wiki/File:A.jpg") }
                jsonPath("$[0].licence") { value("CC-BY-SA-4.0") }
                jsonPath("$[1].position") { value(6) }
                jsonPath("$[1].sourceUrl") { value("https://www.marinakastela.hr/img/b.webp") }
                jsonPath("$[1].caption") { value("Marina Kaštela") }
            }
        storedFiles() shouldHaveSize 2
    }

    @Test
    fun `from-url - a page that is not an image or too large is 400 naming the url, nothing stored`() {
        `when`(fetcher.fetch("https://www.marina.hr/a.jpg")).thenReturn(webp)
        `when`(fetcher.fetch("https://www.marina.hr/gallery.html")).thenThrow(RemoteImageFetcher.Rejected("content type text/html is not an image"))
        `when`(fetcher.fetch("https://www.marina.hr/huge.jpg")).thenThrow(RemoteImageFetcher.Rejected("larger than 10 MB"))
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"urls":[{"url":"https://www.marina.hr/a.jpg","credit":"Foto: M"},{"url":"https://www.marina.hr/gallery.html","credit":"Foto: M"},
                       {"url":"https://www.marina.hr/huge.jpg","credit":"Foto: M"}]}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
                jsonPath("$.message") {
                    value("Invalid request parameters: {urls[1].url=content type text/html is not an image, urls[2].url=larger than 10 MB}")
                }
            }
        storedFiles().shouldBeEmpty()
        rows.isEmpty() shouldBe true
    }

    @Test
    fun `from-url - an image content type with HTML bytes fails the magic-byte check, the earlier file is removed`() {
        `when`(fetcher.fetch("https://www.marina.hr/a.jpg")).thenReturn(webp)
        `when`(fetcher.fetch("https://www.marina.hr/b.jpg")).thenReturn(html)
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"urls":[{"url":"https://www.marina.hr/a.jpg","credit":"Foto: M"},{"url":"https://www.marina.hr/b.jpg","credit":"Foto: M"}]}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Invalid request parameters: {urls[1].url=Invalid image format}") }
            }
        storedFiles().shouldBeEmpty()
    }

    @Test
    fun `from-url - bad url, missing credit, unsafe source link, empty or oversized list are 400 before any download`() {
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"urls":[{"url":"ftp://marina.hr/a.jpg","credit":"Foto: M"},{"url":"https://marina.hr/b.jpg"},
                       {"url":"https://marina.hr/c.jpg","credit":"Foto: M","sourceUrl":"data:text/html,x"},{"credit":"Foto: M"}]}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") {
                    value(
                        "Invalid request parameters: {urls[0].url=must be an http(s) URL of at most 1024 characters, " +
                            "urls[1].credit=required (e.g. \"Foto: Marina Kaštela\"), " +
                            "urls[2].sourceUrl=must be an http(s) URL of at most 1024 characters, urls[3].url=required}",
                    )
                }
            }
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"urls":[]}"""
            }.andExpect { jsonPath("$.message") { value("Invalid request parameters: {urls=at least one url required}") } }
        val eleven = (1..11).joinToString(",") { """{"url":"https://marina.hr/$it.jpg","credit":"Foto: M"}""" }
        mvc
            .post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"urls":[$eleven]}"""
            }.andExpect { jsonPath("$.message") { value("Invalid request parameters: {urls=at most 10 urls per request}") } }
        verifyNoInteractions(fetcher)
    }

    @Test
    fun `update - position, active, caption, credit - a blank credit is 400, an unknown photo 404`() {
        rows[2_000_000_007L] =
            CatalogueImage().apply {
                id = 2_000_000_007L
                locationId = 63
                url = "marina-63/x.webp"
                caption = "Old"
                credit = "Foto: X"
            }
        mvc
            .put("/admin/catalogue-images/2000000007") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"position":2,"active":false,"caption":"  ","credit":"Foto: Marina Kaštela"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.position") { value(2) }
                jsonPath("$.active") { value(false) }
                jsonPath("$.caption") { doesNotExist() }
                jsonPath("$.credit") { value("Foto: Marina Kaštela") }
            }
        mvc
            .put("/admin/catalogue-images/2000000007") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"credit":""}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Invalid request parameters: {credit=must not be blank}") }
            }
        rows[2_000_000_007L]!!.credit shouldBe "Foto: Marina Kaštela"
        mvc
            .put("/admin/catalogue-images/2000000099") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"active":true}"""
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(1602) }
            }
    }

    @Test
    fun `delete - row and file, an unknown photo 404`() {
        mvc
            .multipart("/admin/locations/63/images") {
                file(webpFile("a.webp"))
                param("credit", "Foto: M")
            }.andExpect { status { isOk() } }
        storedFiles() shouldHaveSize 1

        mvc.delete("/admin/catalogue-images/2000000000").andExpect { status { isNoContent() } }
        rows.isEmpty() shouldBe true
        storedFiles().shouldBeEmpty()

        mvc.delete("/admin/catalogue-images/2000000000").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(1602) }
        }
    }

    @Test
    fun `fix-layout is a dry run unless asked`() {
        `when`(mainImageFix.fixLayoutMainImages(true)).thenReturn(MainImageFixResultDto(true, 2, listOf(MainImageFixDto(12663, 218650, 218651)), listOf(4066)))
        mvc.post("/admin/yachts/main-image/fix-layout").andExpect {
            status { isOk() }
            jsonPath("$.dryRun") { value(true) }
            jsonPath("$.layoutMainCount") { value(2) }
            jsonPath("$.fixed[0].yachtId") { value(12663) }
            jsonPath("$.fixed[0].toImageId") { value(218651) }
            jsonPath("$.withoutPhotoYachtIds[0]") { value(4066) }
        }
        verify(mainImageFix).fixLayoutMainImages(true)
        `when`(mainImageFix.fixLayoutMainImages(false)).thenReturn(MainImageFixResultDto(false, 0, emptyList(), emptyList()))
        mvc.post("/admin/yachts/main-image/fix-layout?dryRun=false").andExpect { jsonPath("$.dryRun") { value(false) } }
        verify(mainImageFix).fixLayoutMainImages(false)
    }
}
