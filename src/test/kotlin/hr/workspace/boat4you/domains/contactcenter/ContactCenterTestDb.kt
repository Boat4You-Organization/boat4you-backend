package hr.workspace.boat4you.domains.contactcenter

import com.zaxxer.hikari.HikariDataSource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger

/**
 * A PostgreSQL 18 (prod major) database per test with the REAL chat and contact-center migrations applied, no Spring
 * context / Flyway: the versioned history cannot be replayed on an empty database (V9_18 is a prod data fix that assumes
 * seeded rows), so [CHAT_CHAIN] is every migration that creates or alters `settings` / `ai_chat_*`. One container is
 * shared by the whole JVM and started on first use; each test gets its own database on it.
 */
object ContactCenterTestDb {
    val CHAT_CHAIN =
        listOf(
            "V1_17__add_settings.sql",
            "V9_40__ai_chat_assistant.sql",
            "V9_42__chat_visitor_presence.sql",
            "V9_43__chat_visitor_geo.sql",
            "V9_76__chat_security_lock.sql",
        )

    val CONTACT_CENTER =
        listOf(
            "V9_100__cc_contact_visitor_visit.sql",
            "V9_101__cc_search.sql",
            "V9_102__ai_chat_contact_center.sql",
            "V9_103__cc_tracker_visit_key.sql",
            "V9_107__cc_security_event.sql",
            "V9_108__ai_chat_never_silent.sql",
        )

    private val postgres: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:18-alpine")
            .apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
                withCommand("postgres", "-c", "max_connections=300")
            }.also { it.start() }
    }

    private val counter = AtomicInteger()
    private val dataSources = mutableListOf<HikariDataSource>()
    private val admin: JdbcTemplate by lazy { JdbcTemplate(dataSource(postgres.jdbcUrl)) }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(dataSources) { dataSources.forEach { it.close() } } })
    }

    /** `CREATE DATABASE cc_<n>` on the shared container; the pools keep no idle connection (minimumIdle = 0). */
    fun newDatabase(poolSize: Int = 2): JdbcTemplate {
        val name = "cc_${counter.incrementAndGet()}"
        admin.execute("CREATE DATABASE $name")
        return JdbcTemplate(dataSource(postgres.jdbcUrl.replace("/boat4you_db", "/$name"), poolSize))
    }

    /** Each file in a transaction like Flyway (`BEGIN; <sql>; COMMIT;`). */
    fun apply(
        jdbc: JdbcTemplate,
        files: List<String>,
    ) {
        files.forEach { file ->
            val migration = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $migration; COMMIT;")
        }
    }

    fun ready(poolSize: Int = 2): JdbcTemplate = newDatabase(poolSize).also { apply(it, CHAT_CHAIN + CONTACT_CENTER) }

    private fun dataSource(
        url: String,
        poolSize: Int = 2,
    ) = HikariDataSource()
        .apply {
            jdbcUrl = url
            username = postgres.username
            password = postgres.password
            maximumPoolSize = poolSize
            minimumIdle = 0
        }.also { synchronized(dataSources) { dataSources += it } }
}
