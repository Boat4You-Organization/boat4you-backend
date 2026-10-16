package hr.workspace.boat4you.domains.catalouge.publicslug

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.services.YachtCapacityTestSupport
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import kotlin.test.assertFailsWith

/**
 * The admin maintenance of the frozen public slugs (V9_90, owner rule 16.10.2026) on PostgreSQL 18 (prod major), with
 * the real V9_71 / V9_88 lastmod trigger: the backfill freezes the slug each boat is served under today (no URL and no
 * lastmod moves), only NULL rows, never waiting for a row a sync holds, never a NauSys shell row (the sync freezes it when
 * its model arrives); the refresh recomputes one boat and moves its lastmod only when its URL changes. Both are idempotent.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtPublicSlugServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val LONG_AGO: OffsetDateTime = OffsetDateTime.parse("2000-01-01T00:00:00Z")

        /** yacht as on production (YachtContentModifiedTest, 1.10.2026) + model / manufacturer. */
        private val SCHEMA =
            """
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255));
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255), manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, agency_id bigint, location_id bigint,
                                model_id bigint, deposit numeric, insured_deposit numeric, build_year smallint,
                                launch_year smallint, engine_power smallint, length numeric, draught numeric, beam numeric,
                                water_tank integer, fuel_tank integer, cabins smallint, crew_cabins smallint, wc smallint,
                                crew_wc smallint, berths smallint, crew_berths smallint, max_persons smallint,
                                default_checkin varchar(10), default_checkout varchar(10), mainsail_type varchar(255),
                                mainsail_area numeric, genoa_type varchar(50), genoa_area numeric,
                                registration_number varchar(50), option_approval boolean, option_to_reservation boolean,
                                commision numeric, commision_perc numeric, exclude_discount boolean, max_discount numeric,
                                agency_discount_type varchar(50), max_discount_from_commision numeric,
                                charter_type varchar(250), crewed_type varchar(20), vessel_type varchar(255) NOT NULL,
                                entry_type varchar(255) NOT NULL, sys_active boolean NOT NULL DEFAULT true,
                                main_image_id bigint, deposit_currency varchar(20), crew_number smallint);
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: YachtPublicSlugService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(SCHEMA)
        // like Flyway: V9_71 (lastmod table + trigger), V9_72 + V9_90 (capacity columns, public_slug), V9_88 (full trigger)
        jdbc.execute("BEGIN; ${migration("V9_71__yacht_content_modified.sql")}; COMMIT;")
        YachtCapacityTestSupport.addColumns(jdbc)
        jdbc.execute("BEGIN; ${migration("V9_88__yacht_content_modified_capacity_trigger.sql")}; COMMIT;")
        jdbc.execute("INSERT INTO manufacturer VALUES (1, 'Lagoon'), (2, 'Leopard Catamarans')")
        jdbc.execute("INSERT INTO model VALUES (10, 'Lagoon 42', 1), (20, 'Leopard 42', 2), (30, 'Gulet', NULL)")
        service = YachtPublicSlugService(jdbc, DataSourceTransactionManager(dataSource))
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE yacht, yacht_content_modified")
        boat(1, "Masterpiece", 10)
        boat(2, "Boat4You 424/4/4 Premium", 20)
        boat(3, "Gulet", 30)
        boat(4, "Already Frozen", 10)
        jdbc.update("UPDATE yacht SET public_slug = 'lagoon-42-old-name-4' WHERE id = 4")
        // NauSys shell: tracked before the partner sent its model, inactive, never public
        boat(5, "Shell", null, sysActive = false)
        // as if every boat had last changed long ago
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", LONG_AGO)
    }

    private fun migration(file: String) = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()

    private fun boat(
        id: Long,
        name: String,
        model: Long?,
        sysActive: Boolean = true,
    ) = jdbc.update(
        "INSERT INTO yacht (id, name, model_id, vessel_type, entry_type, sys_active) VALUES (?, ?, ?, 'CATAMARAN', 'EXTERNAL', ?)",
        id,
        name,
        model,
        sysActive,
    )

    private fun slugs(): Map<Long, String?> = jdbc.query("SELECT id, public_slug FROM yacht ORDER BY id") { rs, _ -> rs.getLong(1) to rs.getString(2) }.toMap()

    private fun lastmod(id: Long): OffsetDateTime =
        jdbc.queryForObject("SELECT modified_at FROM yacht_content_modified WHERE yacht_id = ?", OffsetDateTime::class.java, id)!!

    private val today =
        mapOf(
            1L to "lagoon-42-masterpiece-1",
            2L to "leopard-catamarans-leopard-42-boat4you-42444-premium-2",
            3L to "gulet-3",
        )

    @Test
    fun `dry run - counts and shows what would be frozen, writes nothing`() {
        val summary = service.backfill(dryRun = true, actorId = 7)

        summary.missing shouldBe 3
        summary.frozen shouldBe 0
        summary.remaining shouldBe 3
        summary.shells shouldBe 1
        summary.sample shouldBe today
        slugs() shouldBe mapOf(1L to null, 2L to null, 3L to null, 4L to "lagoon-42-old-name-4", 5L to null)
    }

    @Test
    fun `write - freezes today's slug of every NULL row, keeps a frozen one, moves no lastmod, idempotent`() {
        val summary = service.backfill(dryRun = false, actorId = 7)

        summary.missing shouldBe 3
        summary.frozen shouldBe 3
        summary.remaining shouldBe 0
        summary.shells shouldBe 1
        slugs() shouldBe today + (4L to "lagoon-42-old-name-4") + (5L to null)
        // exactly what the YachtPublicSlug fallback served before: SlugUtils on manufacturer, model and public name
        today shouldBe
            mapOf(
                1L to SlugUtils.toSlugWithId("Lagoon", "Lagoon 42", "Masterpiece", 1),
                2L to SlugUtils.toSlugWithId("Leopard Catamarans", "Leopard 42", "Boat4You 424/4/4 Premium", 2),
                3L to SlugUtils.toSlugWithId(null, "Gulet", "Gulet", 3),
            )
        (1L..5L).forEach { lastmod(it) shouldBe LONG_AGO }

        val again = service.backfill(dryRun = false, actorId = 7)
        again.missing shouldBe 0
        again.frozen shouldBe 0
    }

    @Test
    fun `a shell row is never frozen from its name alone - the sync freezes it once its model arrives`() {
        service.backfill(dryRun = false, actorId = 7)
        slugs()[5] shouldBe null

        // the partner sent the model: the sync completes the row in place and freezes the slug with the model in it
        jdbc.update("UPDATE yacht SET model_id = 10, sys_active = true WHERE id = 5")
        jdbc.update("UPDATE yacht SET public_slug = ? WHERE id = 5 AND public_slug IS NULL", SlugUtils.toSlugWithId("Lagoon", "Lagoon 42", "Shell", 5))
        slugs()[5] shouldBe "lagoon-42-shell-5"

        val after = service.backfill(dryRun = false, actorId = 7)
        after.shells shouldBe 0
        after.missing shouldBe 0
    }

    @Test
    fun `a row a sync is writing is skipped, never waited for, and frozen by the next run`() {
        dataSource.connection.use { sync ->
            sync.autoCommit = false
            sync.createStatement().use { it.execute("UPDATE yacht SET name = 'Masterpiece' WHERE id = 1") }

            val summary = service.backfill(dryRun = false, actorId = 7)

            summary.frozen shouldBe 2
            summary.remaining shouldBe 1
            sync.commit()
        }
        slugs()[1] shouldBe null

        service.backfill(dryRun = false, actorId = 7).frozen shouldBe 1
        slugs()[1] shouldBe "lagoon-42-masterpiece-1"
    }

    @Test
    fun `refresh - recomputes one boat from its current name, moves its lastmod only when the URL changes`() {
        jdbc.update("UPDATE yacht SET name = 'New Name' WHERE id = 4")
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", LONG_AGO)

        val result = service.refresh(4, actorId = 7)!!

        result shouldBe YachtPublicSlugService.RefreshResult(4, "lagoon-42-old-name-4", "lagoon-42-new-name-4", urlChanged = true)
        slugs()[4] shouldBe "lagoon-42-new-name-4"
        lastmod(4) shouldNotBe LONG_AGO

        // idempotent: nothing left to change, the lastmod stays
        val moved = lastmod(4)
        service.refresh(4, actorId = 7) shouldBe YachtPublicSlugService.RefreshResult(4, "lagoon-42-new-name-4", "lagoon-42-new-name-4", urlChanged = false)
        lastmod(4) shouldBe moved
    }

    @Test
    fun `refresh of a boat not frozen yet only freezes - the URL it is served under stays, no lastmod move`() {
        service.refresh(1, actorId = 7) shouldBe YachtPublicSlugService.RefreshResult(1, "lagoon-42-masterpiece-1", "lagoon-42-masterpiece-1", urlChanged = false)
        slugs()[1] shouldBe "lagoon-42-masterpiece-1"
        lastmod(1) shouldBe LONG_AGO
    }

    @Test
    fun `refresh - an unknown boat is null, a boat a sync holds is busy and nothing changes`() {
        service.refresh(999, actorId = 7) shouldBe null

        jdbc.update("UPDATE yacht SET name = 'New Name' WHERE id = 4")
        dataSource.connection.use { sync ->
            sync.autoCommit = false
            sync.createStatement().use { it.execute("UPDATE yacht SET build_year = 2019 WHERE id = 4") }

            assertFailsWith<YachtPublicSlugService.BoatBusyException> { service.refresh(4, actorId = 7) }
            sync.rollback()
        }
        slugs()[4] shouldBe "lagoon-42-old-name-4"
    }
}
