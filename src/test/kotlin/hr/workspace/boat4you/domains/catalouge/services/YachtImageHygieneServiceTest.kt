package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.catalouge.dto.BlockedImageDto
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageHygieneRequest
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * The admin image hygiene (V9_91, owner incident 16.10.2026) on PostgreSQL 18 and a real upload directory: the stored
 * partner placeholder found by size + SHA-256 (the real 15,002-byte file as stored on prod), a plan found by its pixels,
 * one image corrected by hand - each moving the boat's card photo like its next sync would.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtImageHygieneServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val MODEL = "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg"
        private const val YACHT = "https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var uploadDir: Path
    private lateinit var service: YachtImageHygieneService

    @BeforeAll
    fun setUp() {
        nu.pattern.OpenCV.loadLocally()
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
            CREATE TABLE yacht (id bigint PRIMARY KEY, sys_active boolean NOT NULL DEFAULT true, main_image_id bigint);
            CREATE TABLE yacht_image (id bigserial PRIMARY KEY, url varchar(255), external_url varchar(255), "position" smallint,
                                      yacht_id bigint NOT NULL REFERENCES yacht (id), main_image boolean, synced boolean);
            """.trimIndent(),
        )
        jdbc.execute("BEGIN; ${ClassPathResource("db/migration/V9_91__yacht_image_hidden_layout_override.sql").inputStream.bufferedReader().readText()}; COMMIT;")
        uploadDir = Files.createTempDirectory("hygiene-uploads")
        service = YachtImageHygieneService(jdbc, YachtMainImageFixService(jdbc), uploadDir.toString())
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE yacht_image, yacht")
    }

    private fun boat(
        id: Long,
        main: Long?,
    ) = jdbc.update("INSERT INTO yacht (id, sys_active, main_image_id) VALUES (?, true, ?)", id, main)

    @Suppress("LongParameterList")
    private fun image(
        id: Long,
        yachtId: Long,
        externalUrl: String,
        position: Int,
        main: Boolean = false,
        file: ByteArray? = null,
    ) {
        val url = "y-$yachtId/$id.webp"
        file?.let {
            Files.createDirectories(uploadDir.resolve("y-$yachtId"))
            Files.write(uploadDir.resolve(url), it)
        }
        jdbc.update(
            "INSERT INTO yacht_image (id, url, external_url, \"position\", yacht_id, main_image, synced) VALUES (?, ?, ?, ?, ?, ?, true)",
            id,
            url,
            externalUrl,
            position,
            yachtId,
            main,
        )
    }

    private val placeholder = ClassPathResource("images/partner-blocked-photo.webp").inputStream.readAllBytes()

    private fun png(mat: Mat): ByteArray {
        val file = Files.createTempFile("img", ".png")
        Imgcodecs.imwrite(file.toString(), mat)
        return Files.readAllBytes(file)
    }

    private fun planBytes(): ByteArray {
        val sheet = Mat(420, 1056, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val black = Scalar(0.0, 0.0, 0.0)
        Imgproc.rectangle(sheet, Point(60.0, 90.0), Point(980.0, 330.0), black, 3)
        listOf(250.0, 420.0, 600.0, 760.0).forEach { x -> Imgproc.line(sheet, Point(x, 90.0), Point(x, 330.0), black, 2) }
        return png(sheet)
    }

    private fun photoBytes(): ByteArray = png(Mat(300, 400, CvType.CV_8UC3).also { Core.randu(it, 0.0, 255.0) })

    private fun hiddenOf(id: Long) =
        jdbc.queryForObject(
            "SELECT hidden::text || '/' || coalesce(hidden_reason, '-') || '/' || main_image::text FROM yacht_image WHERE id = ?",
            String::class.java,
            id,
        )

    private fun mainOf(yachtId: Long) = jdbc.queryForObject("SELECT main_image_id FROM yacht WHERE id = ?", Long::class.javaObjectType, yachtId)

    @Test
    fun `detect-blocked - the stored placeholder by size and hash, model pictures only by default, apply hides and moves the card`() {
        boat(4345, main = 341899)
        image(341899, 4345, MODEL, 0, main = true, file = placeholder)
        image(341900, 4345, YACHT + "n2.jpg", 2, file = photoBytes())
        boat(14837, main = 1)
        image(1, 14837, MODEL, 0, main = true, file = placeholder)
        boat(7, main = 70)
        image(70, 7, "https://ws.nausys.com/CBMS-external/rest/yachtModel/1/pictures/main.jpg", 0, main = true, file = Random(1).nextBytes(15_002)) // same size, other bytes
        image(71, 7, "https://ws.nausys.com/CBMS-external/rest/yachtModel/2/pictures/main.jpg", 1) // file missing
        boat(8, main = 80)
        image(80, 8, YACHT + "n1.jpg", 0, main = true, file = placeholder) // not a model picture

        val dry = service.detectBlocked(dryRun = true, onlyModelPictures = true)
        dry.scanned shouldBe 4
        dry.hashed shouldBe 3
        dry.missingFiles shouldBe 1
        dry.blocked shouldContainExactly listOf(BlockedImageDto(1, 14837, true), BlockedImageDto(341899, 4345, true))
        hiddenOf(341899) shouldBe "false/-/true"

        val applied = service.detectBlocked(dryRun = false, onlyModelPictures = true)
        applied.mainImagesFixed shouldContainExactly listOf(MainImageFixDto(14837, 1, null), MainImageFixDto(4345, 341899, 341900))
        hiddenOf(341899) shouldBe "true/PARTNER_BLOCKED_PHOTO/false"
        mainOf(4345) shouldBe 341900L
        mainOf(14837) shouldBe null

        service.detectBlocked(dryRun = true, onlyModelPictures = true).blocked shouldBe emptyList() // hidden rows are not scanned again
        service.detectBlocked(dryRun = true, onlyModelPictures = false).blocked shouldContainExactly listOf(BlockedImageDto(80, 8, true))
    }

    @Test
    fun `detect-layout - a plan the file name misses, main images only, apply overrides and moves the card to the photo`() {
        boat(4348, main = 75797)
        image(75797, 4348, YACHT + "main.png", 0, main = true, file = planBytes())
        image(75794, 4348, YACHT + "n1.jpg", 1, file = photoBytes())
        boat(9, main = 90)
        image(90, 9, YACHT + "a.jpg", 0, main = true, file = photoBytes())
        image(91, 9, YACHT + "layout.jpg", 1, file = planBytes()) // a layout by its name already: not measured
        image(92, 9, YACHT + "n9.jpg", 2, file = planBytes()) // a plan, but not a main image

        val dry = service.detectLayout(dryRun = true, onlyMain = true, limit = 200, afterId = 0)
        dry.scanned shouldBe 2
        dry.candidates.map { it.imageId } shouldContainExactly listOf(75797L)
        dry.nextAfterId shouldBe null
        service.detectLayout(dryRun = true, onlyMain = false, limit = 200, afterId = 0).candidates.map { it.imageId } shouldContainExactlyInAnyOrder
            listOf(75797L, 92L)
        service.detectLayout(dryRun = true, onlyMain = false, limit = 2, afterId = 0).nextAfterId shouldBe 91L

        shouldThrow<ParameterValidationException> { service.detectLayout(dryRun = false, onlyMain = true, limit = 200, afterId = 0) }
        val applied = service.detectLayout(dryRun = false, onlyMain = true, limit = 200, afterId = 0, imageIds = listOf(75797))
        applied.mainImagesFixed shouldContainExactly listOf(MainImageFixDto(4348, 75797, 75794))
        applied.skippedImageIds shouldBe emptyList()
        jdbc.queryForObject("SELECT layout_override FROM yacht_image WHERE id = 75797", Boolean::class.java) shouldBe true
        mainOf(4348) shouldBe 75794L
        service.detectLayout(dryRun = true, onlyMain = false, limit = 200, afterId = 0).candidates.map { it.imageId } shouldContainExactly listOf(92L)
    }

    @Test
    fun `detect-layout apply - only the reviewed ids, never a row the reviewed page did not show`() {
        // review finding 16.10.2026: an apply re-scanning "the same page" slid past the PATCHed false positives and
        // flagged rows nobody had looked at
        (1L..4L).forEach { boat ->
            boat(boat, main = boat * 10)
            image(boat * 10, boat, YACHT + "main.png", 0, main = true, file = planBytes())
        }
        val dry = service.detectLayout(dryRun = true, onlyMain = true, limit = 2, afterId = 0)
        dry.candidates.map { it.imageId } shouldContainExactlyInAnyOrder listOf(10L, 20L)
        dry.nextAfterId shouldBe 20L

        service.update(20, YachtImageHygieneRequest(layout = false)) // the reviewer's false positive
        val applied = service.detectLayout(dryRun = false, onlyMain = true, limit = 2, afterId = 0, imageIds = listOf(10, 20, 999))
        applied.candidates.map { it.imageId } shouldContainExactly listOf(10L)
        applied.skippedImageIds shouldContainExactly listOf(20L, 999L) // decided by hand / unknown: untouched
        applied.nextAfterId shouldBe null
        val overrides = jdbc.queryForList("SELECT id, layout_override FROM yacht_image ORDER BY id").associate { it["id"] to it["layout_override"] }
        overrides shouldBe mapOf(10L to true, 20L to false, 30L to null, 40L to null) // 30 and 40 were never reviewed
    }

    @Test
    fun `patch - hide, show again, mark as layout or photo, each with the card photo following`() {
        boat(1, main = 10)
        image(10, 1, YACHT + "main.jpg", 0, main = true, file = photoBytes())
        image(11, 1, YACHT + "n1.jpg", 1, file = photoBytes())

        val hidden = service.update(10, YachtImageHygieneRequest(hidden = true))
        hidden.hidden shouldBe true
        hidden.hiddenReason shouldBe YachtImageHygieneService.ADMIN
        hidden.mainImage shouldBe false
        hidden.yachtMainImageId shouldBe 11L

        val shown = service.update(10, YachtImageHygieneRequest(hidden = false))
        shown.hidden shouldBe false
        shown.hiddenReason shouldBe null
        shown.yachtMainImageId shouldBe 11L // the card keeps the photo it has; the next sync re-applies the partner's main

        val layout = service.update(11, YachtImageHygieneRequest(layout = true))
        layout.layout shouldBe true
        layout.layoutOverride shouldBe true
        layout.yachtMainImageId shouldBe 10L

        service.update(11, YachtImageHygieneRequest(layout = false)).layout shouldBe false
        service.update(11, YachtImageHygieneRequest()).layoutOverride shouldBe false // nothing asked, nothing changed
        shouldThrow<ImageNotFoundException> { service.update(424242, YachtImageHygieneRequest(hidden = true)) }
    }
}
