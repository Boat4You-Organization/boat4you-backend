package hr.workspace.boat4you.domains.catalouge.equipment

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V9_74 + V9_75 + R__1_05 v2 (equipment audit 8.10.2026) through the real Flyway on PostgreSQL 18 (prod major), on the
 * two id layouts that exist: prod / b4y-rehearsal (59 = sundeck-cushions, no cockpit-cushions) and the local :5434 copy
 * (59 = cockpit-cushions, 60-107 = prod 59-106), plus a fresh database. Everything is checked by label_code: the same
 * rows end on the same codes in both layouts, the backup holds each changed row once with its original link, and a
 * second run changes nothing.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EquipmentLinkFixMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        /** The columns the three files touch, typed as on prod. */
        private val SCHEMA =
            """
            CREATE TABLE external_system (id serial PRIMARY KEY, name varchar(50) NOT NULL);
            INSERT INTO external_system (id, name) VALUES (1, 'MMK'), (2, 'NauSys');
            CREATE TABLE equipment (id bigserial PRIMARY KEY, name varchar(100) NOT NULL, label_code varchar(100) NOT NULL,
                                    category varchar(31) NOT NULL, match_keys varchar NOT NULL, filter_order smallint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255), sys_active boolean NOT NULL DEFAULT true);
            CREATE TABLE yacht_equipment (id bigserial PRIMARY KEY,
                                          equipment_id bigint REFERENCES equipment (id) ON DELETE SET NULL,
                                          yacht_id bigint NOT NULL REFERENCES yacht (id), name varchar, external_id bigint,
                                          highlight boolean NOT NULL DEFAULT false, quantity numeric(10, 2), comment text);
            CREATE TABLE external_mapping (id bigserial PRIMARY KEY, external_id bigint NOT NULL, system_id bigint NOT NULL,
                                           type varchar(100) NOT NULL, extended_type varchar(100), external_system_id integer NOT NULL);
            INSERT INTO yacht (id, name) VALUES (1, 'MMK one'), (2, 'NauSys two'), (3, 'Custom three'), (4, 'MMK four');
            INSERT INTO external_mapping (external_id, system_id, type, extended_type, external_system_id)
            VALUES (9001, 1, 'Yacht', 'agency_1', 1), (9002, 2, 'Yacht', 'agency_2', 2), (9004, 4, 'Yacht', 'agency_1', 1),
                   (77, 2, 'Location', NULL, 1);
            """.trimIndent()

        /** (row id, yacht, partner item, name, current code) -> expected code after the fix, and its backup section. */
        private data class Row(
            val id: Long,
            val yacht: Long,
            val externalId: Long?,
            val name: String?,
            val before: String?,
            val after: String?,
            val section: String?,
        )

        private val ROWS =
            listOf(
                Row(101, 2, 100490, "Refrigerator", "refrigerator", "fridge", "S1"),
                Row(102, 3, null, null, "bow-thruster-deck", "bow-thruster", "S1"), // a custom yacht: merges cover every row
                Row(114, 1, 37, "Refrigerator", "refrigerator", "fridge", "S1"),
                Row(103, 2, 100664, "Life jackets", "life-buoy", "life-jackets", "S2"),
                Row(104, 1, 100664, "Life jackets", "life-buoy", "life-buoy", null), // same item id on an MMK yacht: not this item
                Row(105, 2, 100623, "Fish finder", "fenders", null, "S2"),
                Row(113, 2, 105558, "Safety equipment", "safety-net", null, "S2"),
                Row(106, 1, -1, "Stereo", "usb-sockets", "audio-system", "S3"), // the collapsed row borrowed USB's link
                Row(107, 4, -1, "Yanmar", "generator", null, "S3"), // not in the list: NULL
                Row(108, 4, -1, "Cockpit cushions", null, "cockpit-cushions", "S3"), // a code that is new on prod
                Row(109, 2, 477829, "Wi-Fi Internet", null, "wifi", "S4"),
                Row(110, 2, 100618, "Echosounder/Depthsounder", null, "depth-sounder", "S4"),
                Row(111, 1, 21, "Wi-Fi & Internet", null, "wifi", "S4"),
                Row(112, 2, 477829, "Wifi (renamed later)", null, null, null), // a name after the snapshot: next sync
                Row(115, 2, 100664, "Life jackets", "life-jackets", "life-jackets", null),
                Row(116, 3, null, "Fridge", "fridge", "fridge", null),
            )

        private fun label(code: String?) = code?.let { "(SELECT id FROM equipment WHERE label_code = '$it')" } ?: "NULL"

        private val SEED_ROWS: String =
            ROWS.joinToString("\n") { r ->
                "INSERT INTO yacht_equipment (id, yacht_id, external_id, name, equipment_id) VALUES (${r.id}, ${r.yacht}, " +
                    "${r.externalId ?: "NULL"}, ${r.name?.let { "'$it'" } ?: "NULL"}, ${label(r.before)});"
            }
    }

    private val sources: Path = Files.createTempDirectory("equipment-fix-migrations")
    private val dataSources = mutableListOf<HikariDataSource>()

    @BeforeAll
    fun copyMigrations() {
        listOf(EquipmentCatalogueFixture.R105, EquipmentCatalogueFixture.V_SCHEMA, EquipmentCatalogueFixture.V_DATA).forEach { path ->
            Files.writeString(sources.resolve(path.substringAfterLast('/')), EquipmentCatalogueFixture.sql(path))
        }
    }

    @AfterAll
    fun tearDown() {
        dataSources.forEach { it.close() }
    }

    private fun database(name: String): JdbcTemplate {
        JdbcTemplate(dataSource(postgres.jdbcUrl)).execute("CREATE DATABASE $name")
        val jdbc = JdbcTemplate(dataSource(postgres.jdbcUrl.replace("/boat4you_db", "/$name")))
        jdbc.execute(SCHEMA)
        return jdbc
    }

    private fun dataSource(url: String) =
        HikariDataSource()
            .apply {
                jdbcUrl = url
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }.also { dataSources += it }

    /** The catalogue as on prod (ids 1-106) or as on :5434 (cockpit-cushions = 59, the rest +1); keys as before the fix. */
    private fun seedCatalogue(
        jdbc: JdbcTemplate,
        shifted: Boolean,
    ) {
        val rows = EquipmentCatalogueFixture.seedRows.filter { it.labelCode != "depth-sounder" && (shifted || it.labelCode != "cockpit-cushions") }
        rows.forEach { row ->
            val id =
                when {
                    !shifted || row.seedOrder <= 58 -> row.seedOrder
                    row.labelCode == "cockpit-cushions" -> 59
                    else -> row.seedOrder + 1
                }
            jdbc.update(
                "INSERT INTO equipment (id, name, label_code, category, match_keys, filter_order) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                row.name,
                row.labelCode,
                row.category,
                "token-match:${row.labelCode.replace('-', ' ')}",
                row.filterOrder,
            )
        }
        jdbc.queryForObject("SELECT setval('equipment_id_seq', (SELECT max(id) FROM equipment))", Long::class.java)
    }

    private fun migrate(jdbc: JdbcTemplate) =
        Flyway
            .configure()
            .dataSource(jdbc.dataSource)
            .locations("filesystem:$sources")
            .baselineOnMigrate(true)
            .baselineVersion("9.73")
            .load()
            .migrate()

    /** The three files once more, each in its own transaction (what a re-run would do). */
    private fun runAgain(jdbc: JdbcTemplate) {
        listOf(EquipmentCatalogueFixture.V_SCHEMA, EquipmentCatalogueFixture.V_DATA, EquipmentCatalogueFixture.R105).forEach { path ->
            jdbc.execute("BEGIN; ${EquipmentCatalogueFixture.sql(path)}; COMMIT;")
        }
    }

    private fun idOf(
        jdbc: JdbcTemplate,
        label: String,
    ): Long = jdbc.queryForObject("SELECT id FROM equipment WHERE label_code = ?", Long::class.java, label)!!

    private fun links(jdbc: JdbcTemplate): Map<Long, String?> =
        jdbc
            .queryForList("SELECT ye.id, e.label_code FROM yacht_equipment ye LEFT JOIN equipment e ON e.id = ye.equipment_id")
            .associate { (it["id"] as Number).toLong() to it["label_code"] as String? }

    private fun snapshot(jdbc: JdbcTemplate): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT * FROM equipment ORDER BY id") +
            jdbc.queryForList("SELECT id, equipment_id FROM yacht_equipment ORDER BY id") +
            jdbc.queryForList("SELECT ye_id, equipment_id_before, section FROM _equipment_link_fix_backup_20261008 ORDER BY ye_id") +
            jdbc.queryForList("SELECT external_system_id, partner_item_id, partner_name_norm, equipment_id FROM partner_equipment_mapping ORDER BY id")

    private fun checkLayout(
        name: String,
        shifted: Boolean,
        cockpitCushionsId: Long,
        depthSounderId: Long,
    ) {
        val jdbc = database(name)
        seedCatalogue(jdbc, shifted)
        jdbc.execute(SEED_ROWS)

        assertEquals(3, migrate(jdbc).migrationsExecuted)
        assertEquals(
            listOf("9.74", "9.75", "R"),
            jdbc
                .queryForList("SELECT coalesce(version, 'R') AS v FROM flyway_schema_history WHERE success AND installed_rank > 1 ORDER BY installed_rank")
                .map { it["v"] },
        )

        // catalogue: 108 codes, new rows where the sequence puts them, every column as R__1_05 says
        assertEquals(cockpitCushionsId, idOf(jdbc, "cockpit-cushions"), name)
        assertEquals(depthSounderId, idOf(jdbc, "depth-sounder"), name)
        val catalogue = jdbc.queryForList("SELECT label_code, name, category, filter_order, match_keys FROM equipment").associateBy { it["label_code"] }
        assertEquals(108, catalogue.size)
        EquipmentCatalogueFixture.seedRows.forEach { row ->
            val db = catalogue.getValue(row.labelCode)
            val actual = listOf(db["name"], db["category"], (db["filter_order"] as Number?)?.toShort(), db["match_keys"])
            assertEquals(listOf(row.name, row.category, row.filterOrder, row.matchKeys), actual, row.labelCode)
        }
        EquipmentCatalogueFixture.ALIASES.forEach { (alias, canonical) ->
            assertEquals(idOf(jdbc, canonical), jdbc.queryForObject("SELECT merged_into_id FROM equipment WHERE label_code = ?", Long::class.java, alias), alias)
        }
        assertEquals(
            3L,
            jdbc.queryForObject("SELECT count(*) FROM equipment WHERE merged_into_id IS NOT NULL", Long::class.java),
        )
        assertEquals(1, jdbc.queryForList("SELECT 1 FROM pg_indexes WHERE indexname = 'equipment_label_code_uq'").size)

        // explicit links: the 80 seed rows, resolved by label
        assertEquals(
            80L,
            jdbc.queryForObject(
                "SELECT count(*) FROM partner_equipment_mapping m JOIN equipment e ON e.id = m.equipment_id",
                Long::class.java,
            ),
        )
        assertEquals(
            idOf(jdbc, "audio-system"),
            jdbc.queryForObject("SELECT equipment_id FROM partner_equipment_mapping WHERE partner_name_norm = 'radio on the flybridge'", Long::class.java),
        )

        // links
        assertEquals(ROWS.associate { it.id to it.after }, links(jdbc), name)
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM yacht_equipment WHERE equipment_id IN (SELECT id FROM equipment WHERE merged_into_id IS NOT NULL)",
                Long::class.java,
            ),
        )

        // backup: every changed row once, its original link and the first section that changed it
        val backup =
            jdbc
                .queryForList(
                    "SELECT b.ye_id, b.section, e.label_code AS before FROM _equipment_link_fix_backup_20261008 b " +
                        "LEFT JOIN equipment e ON e.id = b.equipment_id_before",
                ).associate { (it["ye_id"] as Number).toLong() to (it["section"] as String to it["before"] as String?) }
        assertEquals(ROWS.filter { it.section != null }.associate { it.id to (it.section!! to it.before) }, backup, name)
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM _equipment_link_fix_backup_20261008 " +
                    "WHERE ye_id = 106 AND yacht_id = 1 AND external_system_id = 1 AND name = 'Stereo'",
                Long::class.java,
            ),
        )

        // a second run changes nothing
        val before = snapshot(jdbc)
        runAgain(jdbc)
        assertEquals(before, snapshot(jdbc), "$name: second run")

        // rollback recipe of V9_75 restores every original link
        jdbc.update(
            "UPDATE yacht_equipment ye SET equipment_id = b.equipment_id_before FROM _equipment_link_fix_backup_20261008 b WHERE b.ye_id = ye.id",
        )
        assertEquals(ROWS.associate { it.id to it.before }, links(jdbc), "$name: rollback")
    }

    @Test
    fun `prod layout - new codes take 107 and 108, every row ends on its code, idempotent`() {
        checkLayout("prod_layout", shifted = false, cockpitCushionsId = 107, depthSounderId = 108)
    }

    @Test
    fun `shifted local layout - cockpit-cushions stays 59, depth-sounder takes 108, same links by code`() {
        checkLayout("shifted_layout", shifted = true, cockpitCushionsId = 59, depthSounderId = 108)
    }

    @Test
    fun `fresh database - the fixes skip, R__1_05 creates the whole catalogue in seed order`() {
        val jdbc = database("fresh")
        assertEquals(3, migrate(jdbc).migrationsExecuted)
        assertEquals(
            EquipmentCatalogueFixture.seedRows.map { it.seedOrder.toLong() to it.labelCode },
            jdbc.queryForList("SELECT id, label_code FROM equipment ORDER BY id").map { (it["id"] as Number).toLong() to it["label_code"] },
        )
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM partner_equipment_mapping", Long::class.java))
        val aliasKeys = jdbc.queryForList("SELECT match_keys FROM equipment WHERE label_code IN ('refrigerator', 'bow-thruster-deck', 'sundeck-cushions')")
        assertTrue(aliasKeys.size == 3 && aliasKeys.all { it["match_keys"] == "" })
    }
}
