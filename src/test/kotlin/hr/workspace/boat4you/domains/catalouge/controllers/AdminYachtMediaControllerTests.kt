package hr.workspace.boat4you.domains.catalouge.controllers

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.services.YachtMainImageFixService
import hr.workspace.boat4you.domains.catalouge.services.YachtOwnMediaService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Our own media on a partner boat (V9_92, owner request 17.10.2026: gulet Allure 11769 - 3 partner images, one of them
 * the layout) through the admin and public controllers, the real service and FileSystemService (magic bytes, WebP,
 * `y-{id}/`, `documents/y-{id}/` under a temp upload dir) on PostgreSQL 18 with V9_91 + V9_92 applied.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminYachtMediaControllerTests {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val MMK = "https://www.booking-manager.com/cbm/documents/"
        private const val API = "https://api.boat4you.com"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var uploadDir: Path
    private lateinit var mvc: MockMvc

    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32)
    private val pdf = "%PDF-1.7\n%crew profile\n".toByteArray()

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, partner_name text, public_slug varchar(255),
                                model_id bigint NOT NULL REFERENCES model (id), entry_type varchar(255) NOT NULL,
                                vessel_type varchar(255) NOT NULL, sys_active boolean NOT NULL DEFAULT true, main_image_id bigint);
            CREATE TABLE custom_yacht_details (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, video_url varchar(500));
            CREATE TABLE yacht_image (id bigserial PRIMARY KEY, url varchar(255), external_url varchar(255), "position" smallint,
                                      yacht_id bigint NOT NULL REFERENCES yacht (id), main_image boolean, synced boolean DEFAULT false);
            """.trimIndent(),
        )
        listOf("V9_91__yacht_image_hidden_layout_override.sql", "V9_92__yacht_own_media.sql").forEach {
            jdbc.execute("BEGIN; ${ClassPathResource("db/migration/$it").inputStream.bufferedReader().readText()}; COMMIT;")
        }
        uploadDir = Files.createTempDirectory("own-media-uploads")
        val files = FileSystemService().also { ReflectionTestUtils.setField(it, "uploadDir", uploadDir.toString()) }
        val service = YachtOwnMediaService(jdbc, files, YachtMainImageFixService(jdbc), "$API/")
        mvc =
            MockMvcBuilders
                .standaloneSetup(AdminYachtMediaController(service), YachtCrewProfileController(service))
                .setControllerAdvice(ApiErrorHandler())
                .build()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE yacht_image, custom_yacht_details, yacht, model, manufacturer")
        uploadDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
        jdbc.execute(
            """
            INSERT INTO model (id, name) VALUES (1, 'Gulet');
            INSERT INTO yacht (id, name, model_id, entry_type, vessel_type, main_image_id) VALUES (11769, 'Allure', 1, 'EXTERNAL', 'GULET', 200134);
            INSERT INTO yacht (id, name, partner_name, model_id, entry_type, vessel_type) VALUES (5, 'Allure II', 'ALLURE 2', 1, 'EXTERNAL', 'GULET');
            INSERT INTO yacht_image (id, url, external_url, "position", yacht_id, main_image, synced) VALUES
                (200130, 'y-11769/200130.webp', '${MMK}allure_layout.jpg', 0, 11769, false, true),
                (200134, 'y-11769/200134.webp', '${MMK}allure_main.jpg', 0, 11769, true, true),
                (200179, 'y-11769/200179.webp', '${MMK}allure_deck.jpg', 0, 11769, false, true);
            """.trimIndent(),
        )
    }

    private fun image(
        part: String,
        name: String,
    ) = MockMultipartFile(part, name, "image/webp", webp)

    /** id, position, source, main flag of the boat's images in position order. */
    private fun gallery(yachtId: Long = 11769) =
        jdbc.queryForList(
            "SELECT id || '/' || \"position\" || '/' || source || '/' || coalesce(main_image::text, 'null') FROM yacht_image " +
                "WHERE yacht_id = ? ORDER BY \"position\", id",
            String::class.java,
            yachtId,
        )

    private fun mainImageId(yachtId: Long = 11769) = jdbc.queryForObject("SELECT main_image_id FROM yacht WHERE id = ?", Long::class.java, yachtId)

    private fun ownIds() = jdbc.queryForList("SELECT id FROM yacht_image WHERE source = 'OWN' ORDER BY \"position\"", Long::class.java)

    private fun storedFiles(): List<File> = uploadDir.toFile().walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `the Allure runbook - main photo first, then the rest after it and before the partner's pictures`() {
        mvc
            .multipart("/admin/yachts/11769/main-image") { file(image("file", "Allure-entering-prokljansko-lake.webp")) }
            .andExpect {
                status { isOk() }
                jsonPath("$.images.length()") { value(4) }
                jsonPath("$.images[0].source") { value("OWN") }
                jsonPath("$.images[0].mainImage") { value(true) }
            }
        val main = ownIds().single()
        mainImageId() shouldBe main
        gallery() shouldContainExactly listOf("$main/0/OWN/true", "200130/1/PARTNER/false", "200134/2/PARTNER/false", "200179/3/PARTNER/false")

        mvc
            .multipart("/admin/yachts/11769/images") {
                file(image("files", "Allure-infront-st-nicholas-fort.webp"))
                file(image("files", "AllureE-1.webp"))
            }.andExpect {
                status { isOk() }
                jsonPath("$.yachtId") { value(11769) }
                jsonPath("$.mainImageId") { value(main) }
                jsonPath("$.images.length()") { value(6) }
            }
        mvc.multipart("/admin/yachts/11769/images") { file(image("files", "AllureE-2.webp")) }.andExpect { status { isOk() } }

        val own = ownIds()
        own shouldHaveSize 4
        own.first() shouldBe main
        gallery() shouldContainExactly
            listOf(
                "${own[0]}/0/OWN/true",
                "${own[1]}/1/OWN/false",
                "${own[2]}/2/OWN/false",
                "${own[3]}/3/OWN/false",
                "200130/4/PARTNER/false",
                "200134/5/PARTNER/false",
                "200179/6/PARTNER/false",
            )
        mainImageId() shouldBe main
        jdbc.queryForList("SELECT url FROM yacht_image WHERE source = 'OWN'", String::class.java).forEach { it shouldStartWith "y-11769/" }
        jdbc.queryForObject("SELECT bool_and(synced) FROM yacht_image WHERE source = 'OWN'", Boolean::class.java) shouldBe true
        storedFiles() shouldHaveSize 4

        mvc.get("/admin/yachts/11769/media").andExpect {
            status { isOk() }
            jsonPath("$.images[4].id") { value(200130) }
            jsonPath("$.images[4].layout") { value(true) }
            jsonPath("$.images[4].source") { value("PARTNER") }
        }
    }

    @Test
    fun `upload - a non-image fails the whole request and leaves no file and no row, at most 20 per request, the boat must exist`() {
        mvc
            .multipart("/admin/yachts/11769/images") {
                file(image("files", "ok.webp"))
                file(MockMultipartFile("files", "evil.jpg", "image/jpeg", "<html></html>".toByteArray()))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
            }
        storedFiles().shouldBeEmpty()
        ownIds().shouldBeEmpty()

        mvc
            .multipart("/admin/yachts/11769/images") { repeat(21) { file(image("files", "p$it.webp")) } }
            .andExpect { status { isBadRequest() } }
        mvc.multipart("/admin/yachts/11769/images") {}.andExpect { status { isBadRequest() } }
        mvc.multipart("/admin/yachts/999/images") { file(image("files", "a.webp")) }.andExpect { status { isNotFound() } }
        storedFiles().shouldBeEmpty()
    }

    @Test
    fun `order - the listed images first, the rest after them - unknown, repeated or no ids are refused`() {
        mvc.multipart("/admin/yachts/11769/images") {
            file(image("files", "a.webp"))
            file(image("files", "b.webp"))
        }
        val (a, b) = ownIds()
        mvc
            .put("/admin/yachts/11769/images/order") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"imageIds":[$b, 200179, $a]}"""
            }.andExpect { status { isOk() } }
        gallery() shouldContainExactly
            listOf("$b/0/OWN/false", "200179/1/PARTNER/false", "$a/2/OWN/false", "200130/3/PARTNER/false", "200134/4/PARTNER/true")

        listOf("""{"imageIds":[$a, 42]}""", """{"imageIds":[$a, $a]}""", """{"imageIds":[]}""", "{}").forEach { body ->
            mvc
                .put("/admin/yachts/11769/images/order") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `delete - our own photo with its file, the card photo moves on - a partner picture is 409, an unknown image 404`() {
        mvc.multipart("/admin/yachts/11769/main-image") { file(image("mainImage", "main.webp")) }.andExpect { status { isOk() } }
        mvc.multipart("/admin/yachts/11769/images") { file(image("files", "second.webp")) }
        val (main, second) = ownIds()
        storedFiles() shouldHaveSize 2

        mvc.delete("/admin/yachts/11769/images/200134").andExpect {
            status { isConflict() }
            jsonPath("$.status") { value("PARTNER_IMAGE") }
        }
        mvc.delete("/admin/yachts/11769/images/424242").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(1602) }
        }
        mvc.delete("/admin/yachts/5/images/$main").andExpect { status { isNotFound() } } // not an image of that boat

        mvc.delete("/admin/yachts/11769/images/$main").andExpect {
            status { isOk() }
            jsonPath("$.mainImageId") { value(second) }
        }
        mainImageId() shouldBe second // the next photo by position (ours first), never the layout
        gallery().first() shouldBe "$second/1/OWN/true"
        jdbc.queryForObject("SELECT count(*) FROM yacht_image WHERE main_image", Int::class.java) shouldBe 1
        storedFiles() shouldHaveSize 1
    }

    @Test
    fun `film - YouTube and Vimeo forms are stored, anything else or no videoUrl key is 400, null removes it`() {
        mvc
            .patch("/admin/yachts/11769/media") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"videoUrl":" https://vimeo.com/982130882 "}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.videoUrl") { value("https://vimeo.com/982130882") }
            }
        jdbc.queryForObject("SELECT video_url FROM yacht WHERE id = 11769", String::class.java) shouldBe "https://vimeo.com/982130882"

        listOf("https://evil.example/watch?v=dQw4w9WgXcQ", "javascript:alert(1)", "https://vimeo.com/channels/x").map { """{"videoUrl":"$it"}""" }.plus("{}").forEach { body ->
            mvc
                .patch("/admin/yachts/11769/media") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(1102) }
                }
        }
        jdbc.queryForObject("SELECT video_url FROM yacht WHERE id = 11769", String::class.java) shouldBe "https://vimeo.com/982130882"

        mvc
            .patch("/admin/yachts/11769/media") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"videoUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}"""
            }.andExpect { status { isOk() } }
        mvc
            .patch("/admin/yachts/11769/media") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"videoUrl":null}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.videoUrl") { doesNotExist() }
            }
        mvc
            .patch("/admin/yachts/999/media") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"videoUrl":"https://youtu.be/dQw4w9WgXcQ"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `crew profile - PDF only, stored under documents, served inline with its name and a day of cache, replaced and removed with its file`() {
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect { status { isNotFound() } }
        mvc
            .multipart("/admin/yachts/11769/crew-profile") { file(MockMultipartFile("file", "crew.pdf", "application/pdf", "<html>".toByteArray())) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
            }
        storedFiles().shouldBeEmpty()

        mvc
            .multipart("/admin/yachts/11769/crew-profile") { file(MockMultipartFile("file", "Allure E - Crew Profile.pdf", "application/pdf", pdf)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.crewProfileUrl") { value("$API/public/yachts/11769/crew-profile.pdf") }
            }
        val first = jdbc.queryForObject("SELECT crew_profile_url FROM yacht WHERE id = 11769", String::class.java)!!
        first shouldStartWith "documents/y-11769/"
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_PDF) }
            header { string("Content-Disposition", "inline; filename=\"Gulet Allure - Crew profile.pdf\"") }
            header { string("Cache-Control", "max-age=86400, public") }
            content { bytes(pdf) }
        }

        val second = "%PDF-1.4 v2".toByteArray()
        mvc.multipart("/admin/yachts/11769/crew-profile") { file(MockMultipartFile("file", "v2.pdf", "application/pdf", second)) }
        storedFiles().map { it.readBytes().toList() } shouldContainExactly listOf(second.toList()) // the old file is gone
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect { content { bytes(second) } }

        // a boat that is off the sites, or a path that is not the boat's own folder: 404
        jdbc.update("UPDATE yacht SET sys_active = false WHERE id = 11769")
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect { status { isNotFound() } }
        jdbc.update("UPDATE yacht SET sys_active = true, crew_profile_url = 'documents/y-11769/../../y-11769/200130.webp' WHERE id = 11769")
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect { status { isNotFound() } }
        mvc.get("/public/yachts/gulet-allure-11769x/crew-profile.pdf").andExpect { status { isNotFound() } }
        jdbc.update("UPDATE yacht SET crew_profile_url = ? WHERE id = 11769", "documents/y-11769/" + storedFiles().single().name)

        mvc.delete("/admin/yachts/11769/crew-profile").andExpect {
            status { isOk() }
            jsonPath("$.crewProfileUrl") { doesNotExist() }
        }
        storedFiles().shouldBeEmpty()
        mvc.get("/public/yachts/11769/crew-profile.pdf").andExpect { status { isNotFound() } }
        mvc.delete("/admin/yachts/11769/crew-profile").andExpect { status { isOk() } } // nothing left: still fine
    }

    @Test
    fun `lookup - by id or by public or partner name, the exact id first, with what the boat already has`() {
        jdbc.update("UPDATE yacht SET video_url = 'https://vimeo.com/982130882', crew_profile_url = 'documents/y-11769/x.pdf' WHERE id = 11769")
        mvc.get("/admin/yachts/lookup") { param("q", "allure") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].id") { value(11769) }
            jsonPath("$[0].name") { value("Allure") }
            jsonPath("$[0].modelName") { value("Gulet") }
            jsonPath("$[0].slug") { value("gulet-allure-11769") }
            jsonPath("$[0].entryType") { value("EXTERNAL") }
            jsonPath("$[0].vesselType") { value("GULET") }
            jsonPath("$[0].mainImageId") { value(200134) }
            jsonPath("$[0].imageCount") { value(3) }
            jsonPath("$[0].hasVideo") { value(true) }
            jsonPath("$[0].hasCrewProfile") { value(true) }
            jsonPath("$[1].hasVideo") { value(false) }
        }
        mvc.get("/admin/yachts/lookup") { param("q", "5") }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(5) }
        }
        mvc.get("/admin/yachts/lookup") { param("q", "ALLURE 2") }.andExpect { jsonPath("$[0].id") { value(5) } } // the partner's name
        mvc.get("/admin/yachts/lookup") { param("q", "%") }.andExpect { jsonPath("$.length()") { value(0) } } // LIKE wildcards are text
        mvc
            .get("/admin/yachts/lookup") {
                param("q", "allure")
                param("limit", "1")
            }.andExpect { jsonPath("$.length()") { value(1) } }
        mvc.get("/admin/yachts/lookup") { param("q", " ") }.andExpect { jsonPath("$.length()") { value(0) } }
    }
}
