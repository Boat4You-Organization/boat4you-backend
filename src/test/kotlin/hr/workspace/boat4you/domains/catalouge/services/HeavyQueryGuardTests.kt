package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException.Reason
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 1.10.2026 (Codex audit F2): a facet/listing query abandoned by the web kept its Hikari
 * connection for minutes (22 connections held > 60 s in `getDistribution`). [HeavyQueryGuard]
 * runs those reads in their own read-only transaction with `SET LOCAL statement_timeout` and a
 * transaction timeout. Real Postgres + real JpaTransactionManager, because what has to be proven is
 * Postgres cancelling the statement and the setting NOT leaking onto the pooled connection.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeavyQueryGuardTests {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private lateinit var transactionManager: JpaTransactionManager

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                // ONE connection: the "after" checks must run on the very connection the guarded
                // transaction used, to prove SET LOCAL did not stay on it.
                maximumPoolSize = 1
            }
        entityManagerFactory =
            LocalContainerEntityManagerFactoryBean()
                .apply {
                    dataSource = this@HeavyQueryGuardTests.dataSource
                    // No entities needed — the guard only runs native SQL here.
                    setPackagesToScan("hr.workspace.boat4you.domains.catalouge.services.heavyqueryguardtest")
                    jpaVendorAdapter = HibernateJpaVendorAdapter()
                    setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
                    afterPropertiesSet()
                }.`object`!!
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
        transactionManager = JpaTransactionManager(entityManagerFactory).apply { dataSource = this@HeavyQueryGuardTests.dataSource }
    }

    @AfterAll
    fun tearDown() {
        entityManagerFactory.close()
        dataSource.close()
    }

    private fun guard(
        statementTimeoutMs: Long,
        transactionTimeoutSeconds: Int,
    ) = HeavyQueryGuard(
        transactionManager,
        entityManager,
        distributionPermits = 1,
        searchListPermits = 1,
        waitMs = 100,
        statementTimeoutMs = statementTimeoutMs,
        transactionTimeoutSeconds = transactionTimeoutSeconds,
    )

    private fun sql(query: String): Any? = entityManager.createNativeQuery(query).singleResult

    @Test
    fun `the read runs read-only with the statement timeout, and the pooled connection goes back clean`() {
        val (timeout, readOnly) =
            guard(statementTimeoutMs = 1_500, transactionTimeoutSeconds = 30).read(HeavyQuery.DISTRIBUTION) {
                sql("SELECT current_setting('statement_timeout')") to sql("SELECT current_setting('transaction_read_only')")
            }

        assertEquals("1500ms", timeout)
        assertEquals("on", readOnly)
        // SET LOCAL ends with the transaction: the same (only) pooled connection is back to the default.
        assertEquals("0", JdbcTemplate(dataSource).queryForObject("SHOW statement_timeout", String::class.java))
    }

    @Test
    fun `a statement over the statement timeout is cancelled by Postgres and shed as TIMED_OUT`() {
        val guard = guard(statementTimeoutMs = 500, transactionTimeoutSeconds = 30)

        val startedAt = System.nanoTime()
        val shed =
            assertFailsWith<HeavyQueryBusyException> {
                guard.read(HeavyQuery.SEARCH_LIST) { sql("SELECT pg_sleep(5)") }
            }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(Reason.TIMED_OUT, shed.reason)
        assertEquals(HeavyQuery.SEARCH_LIST, shed.query)
        assert(tookMs < 4_000) { "the 5 s statement was not cancelled at 500 ms (took $tookMs ms)" }
        // The slot and the connection are both free again.
        assertEquals(0, guard.gates.getValue(HeavyQuery.SEARCH_LIST).inFlight)
        assertEquals("0", JdbcTemplate(dataSource).queryForObject("SHOW statement_timeout", String::class.java))
        assertEquals(1, guard.read(HeavyQuery.SEARCH_LIST) { (sql("SELECT 1") as Number).toInt() })
    }

    @Test
    fun `the transaction timeout bounds the whole read, not just each statement`() {
        // Every statement is well under its own 10 s limit; together they overrun the 2 s
        // transaction — the shape of a 9-11 scan distribution on a struggling DB.
        val guard = guard(statementTimeoutMs = 10_000, transactionTimeoutSeconds = 2)

        val startedAt = System.nanoTime()
        val shed =
            assertFailsWith<HeavyQueryBusyException> {
                guard.read(HeavyQuery.DISTRIBUTION) { repeat(5) { sql("SELECT pg_sleep(0.8)") } }
            }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(Reason.TIMED_OUT, shed.reason)
        assert(tookMs < 3_500) { "the read ran $tookMs ms past its 2 s transaction timeout" }
        assertEquals(0, guard.gates.getValue(HeavyQuery.DISTRIBUTION).inFlight)
    }

    @Test
    fun `an ordinary SQL error is not disguised as a 503`() {
        val guard = guard(statementTimeoutMs = 5_000, transactionTimeoutSeconds = 30)

        val error = runCatching { guard.read(HeavyQuery.DISTRIBUTION) { sql("SELECT * FROM no_such_table") } }.exceptionOrNull()

        assert(error != null && error !is HeavyQueryBusyException) { "expected the SQL error itself, got $error" }
        assertEquals(0, guard.gates.getValue(HeavyQuery.DISTRIBUTION).inFlight)
    }
}
