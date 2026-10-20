package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.dialect.identity.IdentityColumnSupport
import org.hibernate.dialect.identity.PostgreSQLIdentityColumnSupport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

/**
 * V9_92 through the real Hibernate mappings and repositories on PostgreSQL 18 (review 17.10.2026): the MMK / NauSys syncs
 * save yacht_image and yacht rows with these repositories, inside their per-agency transaction.
 * - a yacht_image row the sync inserts is PARTNER, an entity with source OWN is stored OWN (insertable);
 * - a sync re-saving one of our own photos cannot turn it PARTNER (updatable = false);
 * - a sync re-saving a boat it loaded before an admin set its film / crew profile keeps both (insertable / updatable = false).
 * Hibernate builds the schema from the entities - the versioned Flyway chain cannot replay on an empty database (V9_18 is a
 * prod data fix that assumes seeded rows) - then the V9_91 / V9_92 columns are dropped and the real migrations add them
 * back, and Hibernate's schema validator (ddl-auto validate on prod) checks every mapping against the result.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect",
    ],
)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // each step commits like the sync's own transaction
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtOwnMediaRepositoryTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val NAUSYS = "https://ws.nausys.com/CBMS-external/rest/yacht/103152/pictures/"
        private const val VIMEO = "https://vimeo.com/982130882"
        private const val CREW_PROFILE = "documents/y-11769/crew.pdf"
    }

    @Autowired
    private lateinit var yachtRepository: YachtRepository

    @Autowired
    private lateinit var yachtImageRepository: YachtImageRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private val tx by lazy { TransactionTemplate(transactionManager) }

    @BeforeAll
    fun migrate() {
        // Hibernate gives a FK column the BIGSERIAL type of the id it references (NOT NULL, own sequence): plain FKs as on prod
        jdbc.execute(
            """
            DO ${'$'}${'$'}
            DECLARE r record;
            BEGIN
                FOR r IN SELECT table_name, column_name FROM information_schema.columns
                         WHERE table_schema = 'public' AND column_name <> 'id' AND column_default LIKE 'nextval(%' LOOP
                    EXECUTE format('ALTER TABLE %I ALTER COLUMN %I DROP DEFAULT, ALTER COLUMN %I DROP NOT NULL', r.table_name, r.column_name, r.column_name);
                END LOOP;
            END ${'$'}${'$'};
            """.trimIndent(),
        )
        // the read-only columns exactly as V9_91 / V9_92 add them (defaults, NOT NULL), not as Hibernate created them
        jdbc.execute(
            "ALTER TABLE yacht_image DROP COLUMN hidden, DROP COLUMN hidden_reason, DROP COLUMN layout_override, DROP COLUMN source; " +
                "ALTER TABLE yacht DROP COLUMN video_url, DROP COLUMN crew_profile_url",
        )
        listOf("V9_91__yacht_image_hidden_layout_override.sql", "V9_92__yacht_own_media.sql").forEach {
            jdbc.execute("BEGIN; ${ClassPathResource("db/migration/$it").inputStream.bufferedReader().readText()}; COMMIT;")
        }
    }

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE yacht_image, yacht, model, manufacturer CASCADE")
        jdbc.execute(
            """
            INSERT INTO manufacturer (id, name) VALUES (1, 'Gulet');
            INSERT INTO model (id, name, manufacturer_id) VALUES (1, 'Gulet', 1);
            INSERT INTO yacht (id, name, model_id, entry_type, vessel_type, sys_active, main_image_id)
                VALUES (11769, 'Allure', 1, 'EXTERNAL', 'GULET', true, 200134);
            INSERT INTO yacht_image (id, url, external_url, "position", yacht_id, main_image, synced)
                VALUES (200134, 'y-11769/200134.webp', '${NAUSYS}main.jpg', 0, 11769, true, true);
            """.trimIndent(),
        )
    }

    private fun sourceOf(imageId: Long) = jdbc.queryForObject("SELECT source FROM yacht_image WHERE id = ?", String::class.java, imageId)

    @Test
    fun `Hibernate validates every mapping against the V9_92 columns, as ddl-auto validate does on prod`() {
        entityManagerFactory.unwrap(SessionFactory::class.java).schemaManager.validateMappedObjects()
        jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'yacht_image' AND column_name = 'source'", String::class.java) shouldBe
            "'PARTNER'::character varying"
    }

    @Test
    fun `a row the sync saves is PARTNER, an entity with source OWN is stored OWN`() {
        val (partner, own) =
            tx.execute {
                val yacht = yachtRepository.getReferenceById(11769)
                val partner = YachtImage().apply {
                    this.yacht = yacht
                    externalUrl = NAUSYS + "n1.jpg"
                    position = 1
                    synced = false
                }
                val own = YachtImage().apply {
                    this.yacht = yacht
                    url = "y-11769/own.webp"
                    synced = true
                    source = YachtImageSource.OWN
                }
                yachtImageRepository.saveAndFlush(partner).id!! to yachtImageRepository.saveAndFlush(own).id!!
            }!!

        sourceOf(partner) shouldBe "PARTNER"
        sourceOf(own) shouldBe "OWN"
    }

    @Test
    fun `a sync re-saving one of our own photos never turns it PARTNER`() {
        jdbc.update("UPDATE yacht_image SET source = 'OWN', external_url = NULL WHERE id = 200134")

        tx.executeWithoutResult {
            val image = yachtImageRepository.findById(200134).get()
            image.source shouldBe YachtImageSource.OWN
            image.position = 7
            image.source = YachtImageSource.PARTNER // even a wrong value in memory is never written
            yachtImageRepository.saveAndFlush(image)
        }

        sourceOf(200134) shouldBe "OWN"
        jdbc.queryForObject("SELECT \"position\" FROM yacht_image WHERE id = 200134", Int::class.java) shouldBe 7
    }

    @Test
    fun `a sync re-saving a boat it loaded before the admin set the film and crew profile keeps both`() {
        tx.executeWithoutResult {
            val yacht = yachtRepository.findById(11769).get() // the agency sync loads the boat: no film, no profile yet
            yacht.videoUrl shouldBe null
            dataSource.connection.use { admin ->
                // PATCH /admin/yachts/11769/media and POST .../crew-profile commit meanwhile
                admin.prepareStatement("UPDATE yacht SET video_url = ?, crew_profile_url = ? WHERE id = 11769").use {
                    it.setString(1, VIMEO)
                    it.setString(2, CREW_PROFILE)
                    it.executeUpdate()
                }
            }
            yacht.name = "Allure E"
            yachtRepository.saveAndFlush(yacht)
        }

        jdbc.queryForMap("SELECT name, video_url, crew_profile_url FROM yacht WHERE id = 11769") shouldBe
            mapOf("name" to "Allure E", "video_url" to VIMEO, "crew_profile_url" to CREW_PROFILE)
    }
}

/**
 * Schema creation only (YachtOwnMediaRepositoryTest): most entities map their IDENTITY id with columnDefinition "BIGSERIAL"
 * / "SERIAL", for which Hibernate writes "BIGSERIAL generated by default as identity" - refused by PostgreSQL (both default
 * and identity). The serial type alone is the identity here.
 */
class SerialIdPostgreSQLDialect : PostgreSQLDialect() {
    override fun getIdentityColumnSupport(): IdentityColumnSupport =
        object : PostgreSQLIdentityColumnSupport() {
            override fun getIdentityColumnString(type: Int): String = ""
        }
}
