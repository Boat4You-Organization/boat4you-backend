package hr.workspace.boat4you

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path

/**
 * The only test that boots the WHOLE application, in the default profile like the api node: on an empty PostgreSQL 18
 * Flyway applies the full chain (with [EmptyDatabaseShims]), Hibernate runs `ddl-auto: validate` over every entity (the
 * V9_94 lesson) and every bean is wired, connected as `boat4you_app` like the app. No web server starts (mock web
 * environment) and nothing outside this machine is contacted: partner and site addresses point at a closed local port.
 *
 * The context is closed after the class (`@DirtiesContext`) instead of staying cached, so its pool, caches and scheduler
 * threads do not live on in the test JVM; Testcontainers stops the database after that.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.datasource.username=boat4you_app",
        "spring.datasource.password=testpass",
        // No default outside the dev/prod profiles; nothing calls them here.
        "application.external.nausys.base-url=http://127.0.0.1:9",
        "application.external.nausys.username=test",
        "application.external.nausys.password=test",
        "application.external.mmk.base-url=http://127.0.0.1:9",
        "application.external.mmk.bearer=test",
        // ChatBlogIndex reads the blog sitemap of this site at startup.
        "chat.site-base-url=http://127.0.0.1:9",
    ],
)
@DirtiesContext
class Boat4youWsApplicationTests {
    @TestConfiguration
    class EmptyDatabase {
        /** Spring Boot hands every [Callback] bean to its Flyway. */
        @Bean
        fun emptyDatabaseShims(): Callback = EmptyDatabaseShims
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        @TempDir
        @JvmStatic
        lateinit var uploadDir: Path

        /** Flyway migrates as the owner of the container's database, the app's own pool is `boat4you_app` (as on prod). */
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
            registry.add("application.upload.directory") { uploadDir.toString() }
        }
    }

    @Autowired private lateinit var flyway: Flyway

    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun contextLoads() {
        val info = flyway.info()
        val entities = entityManagerFactory.metamodel.entities.size
        println("Boat4youWsApplicationTests: Flyway applied ${info.applied().size} migrations (V + R__), validate on $entities entities")
        info.pending().size shouldBe 0
        // The point of this test is the validate run at boot: a profile or property that turns it off must fail here.
        entityManagerFactory.properties["hibernate.hbm2ddl.auto"] shouldBe "validate"
        entities shouldBeGreaterThan 0
    }
}
