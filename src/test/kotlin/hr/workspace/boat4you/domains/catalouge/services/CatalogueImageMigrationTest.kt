package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * V9_89 (catalogue_image, 16.10.2026) on PostgreSQL 18 (prod major), the file in one transaction like Flyway, plus the
 * SQL that reads it (CatalogueImageRepository) and the model-donor SQL (ModelDonorLookup) on the same tables.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogueImageMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val V9_89 = "V9_89__catalogue_image_marina_photos.sql"
        private const val V9_91 = "V9_91__yacht_image_hidden_layout_override.sql"

        private val SCHEMA =
            """
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_code varchar(2) NOT NULL);
            CREATE TABLE location_same_place (location_id bigint NOT NULL REFERENCES location (id) ON DELETE CASCADE,
                                              same_as_location_id bigint NOT NULL REFERENCES location (id) ON DELETE CASCADE,
                                              note text, created_at timestamptz NOT NULL DEFAULT now(),
                                              PRIMARY KEY (location_id, same_as_location_id));
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, agency_id bigint, location_id bigint,
                                model_id bigint NOT NULL, entry_type varchar(255) NOT NULL,
                                sys_active boolean NOT NULL DEFAULT true, main_image_id bigint);
            CREATE TABLE public.yacht_image (id bigserial NOT NULL, url varchar(255), external_url varchar(255),
                                             "position" smallint, yacht_id bigint NOT NULL, main_image boolean,
                                             synced boolean DEFAULT false, CONSTRAINT yacht_image_pk PRIMARY KEY (id));
            """.trimIndent()

        private val SEED =
            """
            INSERT INTO manufacturer VALUES (1, 'Lagoon');
            INSERT INTO model VALUES (812, 'Lagoon 42', 1), (813, 'Lagoon 46', 1);
            INSERT INTO agency (id, name, active, availability_blocked) VALUES
                (1, 'A', true, false), (2, 'B', false, false), (3, 'C', true, true);
            INSERT INTO location VALUES (63, 'Marina Kaštela', 'HR'), (1935, 'Marina Kaštela', 'HR'), (69, 'ACI Split', 'HR'),
                                        (2025, 'Alimos', 'GR');
            INSERT INTO location_same_place (location_id, same_as_location_id) VALUES (63, 1935);
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate

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
        named = NamedParameterJdbcTemplate(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO PUBLIC")
        jdbc.execute(SCHEMA)
        jdbc.execute(SEED)
        migrate(V9_91) // yacht_image.hidden / layout_override, read by the model-donor SQL
    }

    private fun sql(file: String) = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()

    /** The whole file on one connection in one transaction - what Flyway does. */
    private fun migrate(file: String) =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql(file)) }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    private fun photo(
        locationId: Long,
        position: Int,
        active: Boolean = true,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO catalogue_image (location_id, url, \"position\", active) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java,
            locationId,
            "marina-$locationId/$position.webp",
            position,
            active,
        )!!

    private fun yacht(
        id: Long,
        location: Long = 63,
        agency: Long? = 1,
        entry: String = "EXTERNAL",
        active: Boolean = true,
    ) = jdbc.update(
        "INSERT INTO yacht (id, name, agency_id, location_id, model_id, entry_type, sys_active) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        "Boat $id",
        agency,
        location,
        812L,
        entry,
        active,
    )

    /** [photos] real photos and [layouts] layout drawings, positions 0.. in that order; returns the image ids. */
    private fun images(
        yachtId: Long,
        photos: Int,
        layouts: Int = 0,
    ): List<Long> =
        (0 until photos + layouts).map { i ->
            val name = if (i < photos) "photo_$i.jpg" else "deck_layout_$i.jpg"
            jdbc.queryForObject(
                "INSERT INTO yacht_image (url, external_url, \"position\", yacht_id, synced) VALUES (?, ?, ?, ?, true) RETURNING id",
                Long::class.java,
                "y-$yachtId/$i.webp",
                "https://partner.example/$name",
                (photos + layouts - i),
                yachtId,
            )!!
        }

    @Test
    fun `table, index, grants and the id ranges - and a second run changes nothing`() {
        migrate(V9_89)
        migrate(V9_89)

        jdbc.queryForObject(
            "SELECT has_table_privilege('boat4you_app', 'catalogue_image', 'SELECT, INSERT, UPDATE, DELETE')",
            Boolean::class.java,
        ) shouldBe true
        jdbc.queryForObject(
            "SELECT has_sequence_privilege('boat4you_app', 'catalogue_image_id_seq', 'USAGE, SELECT')",
            Boolean::class.java,
        ) shouldBe true
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_catalogue_image_location_active_position'",
            Int::class.java,
        ) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM catalogue_image", Int::class.java) shouldBe 0

        photo(63, 0) shouldBe 2_000_000_000L
        photo(63, 1) shouldBe 2_000_000_001L
        jdbc.queryForObject("SELECT max_value FROM pg_sequences WHERE sequencename = 'yacht_image_id_seq'", Long::class.java) shouldBe
            1_999_999_999L
        jdbc.queryForObject("SELECT kind FROM catalogue_image WHERE id = 2000000000", String::class.java) shouldBe "MARINA"
        jdbc.queryForObject("SELECT active FROM catalogue_image WHERE id = 2000000000", Boolean::class.java) shouldBe true
    }

    @Test
    fun `a re-run takes no lock an agency sync holds - the sequence cap and the FK run on the first apply only`() {
        // an MMK / NauSys agency sync mid-transaction: one new picture (nextval on yacht_image_id_seq), one location written
        fun syncHoldingLocks(): Connection =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply {
                autoCommit = false
                createStatement().use {
                    it.execute("INSERT INTO yacht_image (yacht_id) VALUES (1)")
                    it.execute("UPDATE location SET name = name WHERE id = 69")
                }
            }

        syncHoldingLocks().use { sync ->
            // the first apply waits for the sync and gives up after lock_timeout, rolled back whole: the by-hand retry
            shouldThrow<Exception> { migrate(V9_89) }.message shouldContain "lock timeout"
            jdbc.queryForObject("SELECT to_regclass('public.catalogue_image') IS NULL", Boolean::class.java) shouldBe true
            sync.rollback()
        }
        migrate(V9_89)
        syncHoldingLocks().use { sync ->
            migrate(V9_89) // Flyway's run at boot after the by-hand apply: no wait
            sync.rollback()
        }

        jdbc.queryForObject("SELECT seqmax FROM pg_catalog.pg_sequence WHERE seqrelid = 'yacht_image_id_seq'::regclass", Long::class.java) shouldBe
            1_999_999_999L
    }

    @Test
    fun `a partner image id can never reach the catalogue range`() {
        migrate(V9_89)
        images(1, photos = 1)
        jdbc.execute("SELECT setval('yacht_image_id_seq', 1999999999)")

        shouldThrow<Exception> { images(1, photos = 1) }.message shouldContain "reached maximum value"
    }

    @Test
    fun `only MARINA rows in the catalogue range, and a deleted location takes its photos along`() {
        migrate(V9_89)

        shouldThrow<Exception> {
            jdbc.update("INSERT INTO catalogue_image (kind, location_id, url) VALUES ('MODEL_SAMPLE', 63, 'x.webp')")
        }.message shouldContain "catalogue_image_kind_check"
        shouldThrow<Exception> {
            jdbc.update("INSERT INTO catalogue_image (id, location_id, url) VALUES (5, 63, 'x.webp')")
        }.message shouldContain "catalogue_image_id_range"
        shouldThrow<Exception> {
            jdbc.update("INSERT INTO catalogue_image (location_id, url) VALUES (99999, 'x.webp')")
        }.message shouldContain "foreign key"

        photo(69, 0)
        jdbc.update("DELETE FROM location WHERE id = 69")
        jdbc.queryForObject("SELECT count(*) FROM catalogue_image", Int::class.java) shouldBe 0
    }

    @Test
    fun `active marina photos of the given rows, by position then age - and the same-place pair in both directions`() {
        migrate(V9_89)
        val p2 = photo(63, 2)
        val p0 = photo(1935, 0)
        photo(63, 1, active = false)
        val p1 = photo(63, 1)
        val p1b = photo(1935, 1)
        photo(69, 0)

        fun active(vararg ids: Long) =
            named.queryForList(
                CatalogueImageRepository.ACTIVE_BY_LOCATION_IDS_SQL.replace("ci.*", "ci.id"),
                mapOf("locationIds" to ids.toList()),
                Long::class.java,
            )

        active(63, 1935) shouldContainExactly listOf(p0, p1, p1b, p2)
        active(63) shouldContainExactly listOf(p1, p2)

        fun partners(id: Long) = named.queryForList(CatalogueImageRepository.SAME_PLACE_PARTNER_IDS_SQL, mapOf("locationId" to id), Long::class.java)

        partners(63) shouldContainExactly listOf(1935L)
        partners(1935) shouldContainExactly listOf(63L)
        partners(69) shouldContainExactly emptyList()
    }

    @Test
    fun `the entity passes ddl-auto validate on V9_89, and the native gallery query maps to it`() {
        migrate(V9_89)
        val registry =
            StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", postgres.jdbcUrl)
                .applySetting("hibernate.connection.username", postgres.username)
                .applySetting("hibernate.connection.password", postgres.password)
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build()
        val sessionFactory = MetadataSources(registry).addAnnotatedClass(CatalogueImage::class.java).buildMetadata().buildSessionFactory()
        try {
            val saved =
                CatalogueImage().apply {
                    locationId = 63L
                    url = "marina-63/a.webp"
                    credit = "Foto: Marina Kaštela"
                    position = 1
                }
            sessionFactory.inTransaction { it.persist(saved) }
            saved.id shouldBe 2_000_000_000L

            val read =
                sessionFactory.fromSession {
                    it
                        .createNativeQuery(CatalogueImageRepository.ACTIVE_BY_LOCATION_IDS_SQL, CatalogueImage::class.java)
                        .setParameterList("locationIds", listOf(63L, 1935L))
                        .resultList
                }
            read.map { listOf(it.id, it.kind, it.locationId, it.url, it.credit, it.position, it.active) } shouldContainExactly
                listOf(listOf(2_000_000_000L, "MARINA", 63L, "marina-63/a.webp", "Foto: Marina Kaštela", 1.toShort(), true))
            // the JPQL Spring Data derives for findByLocationIdOrderByPositionAscIdAsc (admin list)
            sessionFactory.fromSession {
                it
                    .createSelectionQuery("select c from CatalogueImage c where c.locationId = :id order by c.position asc, c.id asc", CatalogueImage::class.java)
                    .setParameter("id", 63L)
                    .resultList
                    .map { image -> image.id }
            } shouldContainExactly listOf(2_000_000_000L)
        } finally {
            sessionFactory.close()
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }

    @Test
    fun `model donor - a served boat of the model with 4 real photos, same country first, then the most photos`() {
        migrate(V9_89)
        val lookup = ModelDonorLookup(jdbc)
        yacht(1) // the boat asking: only a layout
        images(1, photos = 0, layouts = 1)
        yacht(2, location = 2025) // GR, 9 real photos
        images(2, photos = 9)
        yacht(3) // HR, 4 real photos + 3 layouts (7 stored)
        images(3, photos = 4, layouts = 3)
        yacht(4) // HR, 3 real photos + 3 layouts: not enough real photos
        images(4, photos = 3, layouts = 3)
        yacht(5) // HR, 5 real photos
        images(5, photos = 5)

        lookup.donorFor(812, "HR") shouldBe ModelDonor(5)
        lookup.donorFor(812, "GR") shouldBe ModelDonor(2)
        lookup.donorFor(812, null) shouldBe ModelDonor(2) // no country to prefer: the most photos
        lookup.donorFor(813, "HR") shouldBe ModelDonor(null)
    }

    @Test
    fun `model donor - never a retired boat or one of an inactive or blocked agency, a custom boat may be one`() {
        migrate(V9_89)
        val lookup = ModelDonorLookup(jdbc)
        yacht(10, active = false)
        images(10, photos = 9)
        yacht(11, agency = 2)
        images(11, photos = 9)
        yacht(12, agency = 3)
        images(12, photos = 9)
        yacht(13, agency = null)
        images(13, photos = 9)

        lookup.donorFor(812, "HR") shouldBe ModelDonor(null)

        yacht(14, agency = null, entry = "CUSTOM")
        images(14, photos = 4)
        lookup.donorFor(812, "HR") shouldBe ModelDonor(14)
    }

    @Test
    fun `model donor - hidden images and layout_override drawings are no photos (V9_91)`() {
        migrate(V9_89)
        val lookup = ModelDonorLookup(jdbc)
        yacht(20)
        val ids = images(20, photos = 5) // positions 5, 4, 3, 2, 1
        jdbc.update("UPDATE yacht_image SET hidden = true WHERE id = ?", ids[0]) // the partner's placeholder
        jdbc.update("UPDATE yacht_image SET layout_override = true WHERE id = ?", ids[1]) // a plan named like a photo

        lookup.donorFor(812, "HR") shouldBe ModelDonor(null) // 3 real photos left: not enough
        lookup.donorPhotoIds(20) shouldContainExactly ids.drop(2).reversed()

        jdbc.update("UPDATE yacht_image SET layout_override = false WHERE id = ?", ids[1])
        lookup.donorPhotoIds(20) shouldContainExactly ids.drop(1).reversed()
    }

    @Test
    fun `donor photos - stored, not layouts, in gallery order`() {
        migrate(V9_89)
        val lookup = ModelDonorLookup(jdbc)
        yacht(3)
        val ids = images(3, photos = 4, layouts = 2) // positions 6, 5, 4, 3 (photos), 2, 1 (layouts)
        jdbc.update("INSERT INTO yacht_image (url, external_url, \"position\", yacht_id) VALUES (NULL, 'https://partner.example/x.jpg', 0, 3)")

        lookup.donorPhotoIds(3) shouldContainExactly ids.take(4).reversed()
    }
}
