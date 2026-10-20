package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixDto
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * POST /admin/yachts/main-image/fix-layout on PostgreSQL 18 (prod major), yacht / yacht_image with the columns the
 * repair reads (16.10.2026: 11 offered boats had the layout drawing as card photo although they have photos).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtMainImageFixServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val MMK = "https://www.booking-manager.com/cbm/documents/"
        private const val NAUSYS = "https://ws.nausys.com/CBMS-external/rest/yacht/103152/pictures/"
        private const val NAUSYS_MODEL = "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: YachtMainImageFixService

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
            CREATE TABLE yacht (id bigint PRIMARY KEY, sys_active boolean NOT NULL DEFAULT true, main_image_id bigint);
            CREATE TABLE yacht_image (id bigserial PRIMARY KEY, url varchar(255), external_url varchar(255), "position" smallint,
                                      yacht_id bigint NOT NULL REFERENCES yacht (id), main_image boolean, synced boolean,
                                      hidden boolean NOT NULL DEFAULT false, hidden_reason text, layout_override boolean,
                                      source varchar(16) NOT NULL DEFAULT 'PARTNER');
            """.trimIndent(),
        )
        service = YachtMainImageFixService(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE yacht_image, yacht")
        // 12663: MMK, main = the layout, photos at positions 1 and 2 -> the position-1 photo
        boat(12663, main = 1, active = true)
        image(1, 12663, MMK + "36397280967800260_1695203065343_L55_layout_5.jpg", 0, main = true)
        image(2, 12663, MMK + "37114131586300260_themoon_2071.jpg", 2)
        image(3, 12663, MMK + "36397340967800260_L55_vistalato.jpg", 1)
        // 1835: NauSys, main = layout.jpg; its lowest-position photo is not stored yet (unsynced) -> the stored one
        boat(1835, main = 10, active = true)
        image(10, 1835, NAUSYS + "layout.jpg", 0, main = true)
        image(11, 1835, NAUSYS + "main.jpg", 1, stored = false)
        image(12, 1835, NAUSYS + "n1.jpg", 2)
        // 4066: only the layout -> left alone, listed
        boat(4066, main = 20, active = true)
        image(20, 4066, NAUSYS + "layout.png", 0, main = true)
        // 777: main is already a photo -> not touched
        boat(777, main = 30, active = true)
        image(30, 777, MMK + "a_exterior.jpg", 0, main = true)
        image(31, 777, MMK + "b_layout.jpg", 1)
        // 900: retired boat with a layout main -> not touched
        boat(900, main = 40, active = false)
        image(40, 900, MMK + "x_layout.jpg", 0, main = true)
        image(41, 900, MMK + "x_photo.jpg", 1)
        // V9_91, owner incident 16.10.2026 - 4345 (FuFu): main = the NauSys model picture, the partner's blocked-photo
        // placeholder, hidden -> the lowest-position photo (the layout skipped)
        boat(4345, main = 50, active = true)
        image(50, 4345, NAUSYS_MODEL, 0, main = true, hidden = true)
        image(51, 4345, NAUSYS + "layout.png", 1)
        image(52, 4345, NAUSYS + "n2.jpg", 2)
        image(53, 4345, NAUSYS + "n3.jpg", 3)
        // 14837: the placeholder is its only image -> no card photo at all (the sites show their default)
        boat(14837, main = 60, active = true)
        image(60, 14837, NAUSYS_MODEL, 0, main = true, hidden = true)
        // 4400: main = a plan the file name does not betray, layout_override set -> the photo
        boat(4400, main = 80, active = true)
        image(80, 4400, NAUSYS + "main.png", 0, main = true, layoutOverride = true)
        image(81, 4400, NAUSYS + "n1.jpg", 1)
        // 5000: main_image_id names an image that no longer exists -> the photo
        boat(5000, main = 999, active = true)
        image(90, 5000, NAUSYS + "n1.jpg", 1)
    }

    private fun boat(
        id: Long,
        main: Long,
        active: Boolean,
    ) = jdbc.update("INSERT INTO yacht (id, sys_active, main_image_id) VALUES (?, ?, ?)", id, active, main)

    @Suppress("LongParameterList")
    private fun image(
        id: Long,
        yachtId: Long,
        externalUrl: String,
        position: Int,
        main: Boolean = false,
        stored: Boolean = true,
        hidden: Boolean = false,
        layoutOverride: Boolean? = null,
    ) = jdbc.update(
        "INSERT INTO yacht_image (id, url, external_url, \"position\", yacht_id, main_image, synced, hidden, layout_override) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        if (stored) "y-$yachtId/$id.webp" else null,
        externalUrl,
        position,
        yachtId,
        main,
        stored,
        hidden,
        layoutOverride,
    )

    private fun mainOf(yachtId: Long) = jdbc.queryForObject("SELECT main_image_id FROM yacht WHERE id = ?", Long::class.javaObjectType, yachtId)

    private val expected =
        listOf(
            MainImageFixDto(1835, 10, 12),
            MainImageFixDto(4345, 50, 52),
            MainImageFixDto(4400, 80, 81),
            MainImageFixDto(5000, 999, 90),
            MainImageFixDto(12663, 1, 3),
            MainImageFixDto(14837, 60, null),
        )

    @Test
    fun `dry run lists the boats and changes nothing`() {
        val result = service.fixLayoutMainImages(dryRun = true)
        result.dryRun shouldBe true
        result.layoutMainCount shouldBe 4
        result.hiddenMainCount shouldBe 2
        result.missingMainCount shouldBe 1
        result.fixed shouldContainExactly expected
        result.withoutPhotoYachtIds shouldContainExactly listOf(4066L)
        listOf(12663L to 1L, 1835L to 10L, 4066L to 20L, 777L to 30L, 900L to 40L, 4345L to 50L, 14837L to 60L, 4400L to 80L, 5000L to 999L)
            .forEach { (boat, main) -> mainOf(boat) shouldBe main }
    }

    @Test
    fun `a real run sets the first stored photo, leaves the rest - and a second run finds nothing to do`() {
        service.fixLayoutMainImages(dryRun = false).fixed shouldContainExactly expected
        listOf(12663L to 3L, 1835L to 12L, 4066L to 20L, 777L to 30L, 900L to 40L, 4345L to 52L, 14837L to null, 4400L to 81L, 5000L to 90L)
            .forEach { (boat, main) -> mainOf(boat) shouldBe main }

        val again = service.fixLayoutMainImages(dryRun = false)
        again.layoutMainCount shouldBe 1
        again.hiddenMainCount shouldBe 0
        again.missingMainCount shouldBe 0
        again.fixed shouldBe emptyList()
        again.withoutPhotoYachtIds shouldContainExactly listOf(4066L)
    }

    @Test
    fun `repickMain - one boat to what its next sync would write, a no-op when it already is`() {
        service.repickMain(4345) shouldBe MainImageFixDto(4345, 50, 52)
        mainOf(4345) shouldBe 52L
        service.repickMain(4345) shouldBe null
        service.repickMain(777) shouldBe null // already its photo
        service.repickMain(424242) shouldBe null // no such boat

        // an admin shows the only image of a boat without a card photo again
        jdbc.update("UPDATE yacht SET main_image_id = NULL WHERE id = 14837")
        jdbc.update("UPDATE yacht_image SET hidden = false WHERE id = 60")
        service.repickMain(14837) shouldBe MainImageFixDto(14837, null, 60)
    }

    @Test
    fun `repickMain keeps an OWN current main although a partner picture carries the main flag too`() {
        // V9_92, review 17.10.2026: after the documented sync race the partner's photo is flagged as well; an unrelated
        // admin decision (PATCH hidden, DELETE of another own photo) must not move the card to it
        boat(11769, main = 71, active = true)
        image(70, 11769, MMK + "allure_main.jpg", 2, main = true) // the partner's, read first
        image(71, 11769, "", 0, main = true)
        jdbc.update("UPDATE yacht_image SET external_url = NULL, source = 'OWN' WHERE id = 71")

        service.repickMain(11769) shouldBe null
        mainOf(11769) shouldBe 71L
    }
}
