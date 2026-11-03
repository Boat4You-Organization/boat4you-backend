package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.service.SocialPublisher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * V9_118 on a real PostgreSQL (18, the prod major, through Testcontainers; or the database in SOCIAL_TEST_JDBC_URL when
 * set, for machines without Docker): the tables apply cleanly, the publisher's claim takes a due target exactly once and
 * skips a row another transaction holds, and the uniqueness rules hold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialSchemaTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate

    @BeforeAll
    fun setUp() {
        // SOCIAL_TEST_JDBC_URL must name an EMPTY database (the migration creates its tables there).
        val ds =
            System.getenv("SOCIAL_TEST_JDBC_URL")?.let { url ->
                DriverManagerDataSource(url, System.getenv("SOCIAL_TEST_JDBC_USER") ?: "postgres", System.getenv("SOCIAL_TEST_JDBC_PASSWORD") ?: "")
            } ?: postgres.let { DriverManagerDataSource(it.jdbcUrl, it.username, it.password) }
        jdbc = JdbcTemplate(ds)
        tx = TransactionTemplate(DataSourceTransactionManager(ds))
        // The whole file as Flyway runs it (one script); the GRANTs need the boat4you_app role, as on prod.
        jdbc.execute(ClassPathResource("db/migration/V9_118__social_publisher.sql").inputStream.readAllBytes().decodeToString())
    }

    private fun q(sql: String) = sql

    private fun seedTargets(): List<Long> {
        jdbc.execute(q("TRUNCATE social_post_target, social_post, social_account RESTART IDENTITY CASCADE"))
        jdbc.update(q("INSERT INTO social_account (platform, external_id, name, status) VALUES ('FACEBOOK', 'p1', 'Boat4You', 'CONNECTED')"))
        jdbc.update(q("INSERT INTO social_account (platform, external_id, name, status) VALUES ('INSTAGRAM', 'i1', 'boat4you', 'CONNECTED')"))
        jdbc.update(q("INSERT INTO social_account (platform, external_id, name, status) VALUES ('X', 'x1', 'Boat4You', 'CONNECTED')"))
        jdbc.update(q("INSERT INTO social_post (text, status, source) VALUES ('hi', 'SCHEDULED', 'ADMIN')"))
        jdbc.update(q("INSERT INTO social_post_target (post_id, account_id, status, due_at) VALUES (1, 1, 'SCHEDULED', now() - interval '1 minute')"))
        jdbc.update(q("INSERT INTO social_post_target (post_id, account_id, status, due_at) VALUES (1, 2, 'SCHEDULED', now() + interval '1 hour')"))
        jdbc.update(q("INSERT INTO social_post_target (post_id, account_id, status, due_at) VALUES (1, 3, 'PENDING', now() - interval '1 minute')"))
        return listOf(1L, 2L, 3L)
    }

    private fun claim(limit: Int = 10): List<Long> = tx.execute { jdbc.queryForList(q(SocialPublisher.CLAIM_SQL), Long::class.java, limit) }!!

    @Test
    fun `a due scheduled target is claimed exactly once, future and draft ones are not`() {
        seedTargets()
        claim() shouldContainExactly listOf(1L)
        claim() shouldContainExactly emptyList()
        jdbc.queryForObject(q("SELECT status || ':' || attempts FROM social_post_target WHERE id = 1"), String::class.java) shouldBe "PUBLISHING:1"
    }

    @Test
    fun `a row another transaction holds is skipped, not waited for`() {
        seedTargets()
        jdbc.update(q("UPDATE social_post_target SET due_at = now() - interval '1 minute' WHERE id = 2"))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                tx.executeWithoutResult {
                    jdbc.queryForList(q("SELECT id FROM social_post_target WHERE id = 1 FOR UPDATE"))
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        locked.await(10, TimeUnit.SECONDS) shouldBe true
        claim() shouldContainExactly listOf(2L)
        release.countDown()
        holder.join()
        claim() shouldContainExactly listOf(1L)
    }

    @Test
    fun `one row per network account, one channel per account in a post, one AI run per site and day`() {
        seedTargets()
        (runCatching { dup() }.exceptionOrNull() is DataIntegrityViolationException) shouldBe true
        runCatching { jdbc.update(q("INSERT INTO social_post_target (post_id, account_id, status) VALUES (1, 1, 'PENDING')")) }.isFailure shouldBe true
        jdbc.update(q("INSERT INTO social_ai_run (site, run_date, status) VALUES ('boat4you', DATE '2026-11-04', 'RUNNING')"))
        runCatching { jdbc.update(q("INSERT INTO social_ai_run (site, run_date, status) VALUES ('boat4you', DATE '2026-11-04', 'RUNNING')")) }.isFailure shouldBe
            true
    }

    private fun dup() = jdbc.update(q("INSERT INTO social_account (platform, external_id, name, status) VALUES ('FACEBOOK', 'p1', 'again', 'CONNECTED')"))

    companion object {
        val postgres: PostgreSQLContainer<Nothing> by lazy {
            PostgreSQLContainer<Nothing>("postgres:18-alpine")
                .apply {
                    withDatabaseName("boat4you_db")
                    withInitScript("init/00_roles.sql")
                }.also { it.start() }
        }
    }
}
