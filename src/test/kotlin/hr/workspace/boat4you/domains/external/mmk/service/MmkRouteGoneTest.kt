package hr.workspace.boat4you.domains.external.mmk.service

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkD3Mode
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate

/**
 * ROUTE_GONE for MMK (3.11.2026, `MMK_D3_MODE`): after one yacht's answer is upserted, a FREE row of dates and product
 * the answer carried, but only with other routes, is a route the partner stopped sending (18643: Pula rows on sale
 * months after the boat moved to Propriano).
 */
class MmkRouteGoneTest {
    companion object {
        private const val PROPRIANO = 813L
        private const val PULA = 1932L
        private const val OLBIA = 30L
        private val WEEK: LocalDate = LocalDate.of(2027, 7, 3)
        private val NEXT_WEEK: LocalDate = WEEK.plusDays(7)

        private fun offer(
            id: Long,
            from: Long,
            to: Long = from,
            dateFrom: LocalDate = WEEK,
            status: OfferStatus = OfferStatus.FREE,
            product: CharterType = CharterType.BAREBOAT,
        ) = Offer().apply {
            this.id = id
            this.dateFrom = dateFrom
            this.dateTo = dateFrom.plusDays(7)
            this.status = status
            this.product = product
            locationFrom = Location().apply { this.id = from }
            locationTo = Location().apply { this.id = to }
        }

        private fun answer(
            vararg routes: Pair<Long?, Long?>,
            dateFrom: LocalDate = WEEK,
            product: CharterType = CharterType.BAREBOAT,
        ) = MmkRouteGone.Answer().apply { routes.forEach { add(dateFrom, dateFrom.plusDays(7), product, it.first, it.second) } }
    }

    @Nested
    inner class WhichRowsAreStale {
        @Test
        fun `a FREE row whose dates and product came only with another route is stale`() {
            MmkRouteGone.staleRows(listOf(offer(1, PULA), offer(2, PROPRIANO)), answer(PROPRIANO to PROPRIANO)) shouldBe listOf(1L)
        }

        @Test
        fun `every route the answer carried stays, also a one-way and a second live base`() {
            val existing = listOf(offer(1, PULA), offer(2, PROPRIANO), offer(3, PROPRIANO, OLBIA), offer(4, OLBIA))
            MmkRouteGone.staleRows(existing, answer(PROPRIANO to PROPRIANO, PROPRIANO to OLBIA, OLBIA to OLBIA)) shouldBe listOf(1L)
        }

        @Test
        fun `dates the answer did not carry are never judged`() {
            MmkRouteGone.staleRows(listOf(offer(1, PULA, dateFrom = NEXT_WEEK)), answer(PROPRIANO to PROPRIANO)) shouldBe emptyList()
        }

        @Test
        fun `another product of the same dates is not judged by this one`() {
            MmkRouteGone.staleRows(listOf(offer(1, PULA, product = CharterType.CREWED)), answer(PROPRIANO to PROPRIANO)) shouldBe emptyList()
        }

        @Test
        fun `dates whose answer named a base we cannot map are not judged`() {
            MmkRouteGone.staleRows(listOf(offer(1, PULA)), answer(PROPRIANO to PROPRIANO, null to null)) shouldBe emptyList()
        }

        @Test
        fun `only FREE rows, never an option, a pre-reserved or an unavailable row`() {
            val existing =
                listOf(
                    offer(1, PULA, status = OfferStatus.OPTION),
                    offer(2, PULA, status = OfferStatus.OPTION_WAITING),
                    offer(3, PULA, status = OfferStatus.UNAVAILABLE),
                )
            MmkRouteGone.staleRows(existing, answer(PROPRIANO to PROPRIANO)) shouldBe emptyList()
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class AgainstPostgres {
        private val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private lateinit var dataSource: HikariDataSource
        private lateinit var jdbc: JdbcTemplate
        private val switches = mock(RealPriceSwitches::class.java)
        private val entityManager = mock(EntityManager::class.java)
        private lateinit var routeGone: MmkRouteGone
        private val yacht = Yacht().apply { id = 18643 }

        @BeforeAll
        fun schema() {
            postgres.start()
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
                CREATE TABLE offer (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL, date_to date NOT NULL,
                                    status varchar(31) NOT NULL, ext_status varchar(50), client_price numeric NOT NULL);
                CREATE TABLE reservation_flow (id bigint PRIMARY KEY, offer_id bigint);
                CREATE TABLE reservation_extras (id bigint PRIMARY KEY);
                """.trimIndent(),
            )
            jdbc.execute("BEGIN; ${ClassPathResource("db/migration/V9_82__real_price_evidence.sql").getContentAsString(Charsets.UTF_8)}; COMMIT;")
            routeGone = MmkRouteGone(switches, NamedParameterJdbcTemplate(jdbc), entityManager)
        }

        @AfterAll
        fun close() {
            dataSource.close()
            postgres.stop()
        }

        @BeforeEach
        fun rows() {
            org.mockito.Mockito.clearInvocations(entityManager)
            jdbc.execute("TRUNCATE offer, reservation_flow, partner_offer_flip_log")
            // 1 and 2 = Pula rows the answer dropped (2 is being booked), 3 = the Propriano row the answer carried
            jdbc.execute(
                """
                INSERT INTO offer VALUES (1, 18643, '2027-07-03', '2027-07-10', 'FREE', '1', 3456),
                                         (2, 18643, '2027-07-03', '2027-07-10', 'FREE', '1', 3456),
                                         (3, 18643, '2027-07-03', '2027-07-10', 'FREE', '1', 2592);
                INSERT INTO reservation_flow VALUES (9, 2);
                """.trimIndent(),
            )
        }

        private val rowsInSession = listOf(offer(1, PULA), offer(2, PULA), offer(3, PROPRIANO))

        private fun apply(mode: MmkD3Mode): Int {
            `when`(switches.mmkD3Mode).thenReturn(mode)
            `when`(entityManager.contains(rowsInSession[0])).thenReturn(true)
            return routeGone.apply(yacht, rowsInSession, answer(PROPRIANO to PROPRIANO), "test")
        }

        private fun detached() = mockingDetails(entityManager).invocations.filter { it.method.name == "detach" }.map { (it.arguments[0] as Offer).id }

        private fun statuses() = jdbc.queryForList("SELECT id || ' ' || status || ' ' || ext_status FROM offer ORDER BY id", String::class.java)

        @Test
        fun `WRITE hides the dropped route, never a row someone is booking, and logs each flip for an undo`() {
            apply(MmkD3Mode.WRITE) shouldBe 1
            statuses() shouldBe listOf("1 UNAVAILABLE ROUTE_GONE", "2 FREE 1", "3 FREE 1")
            jdbc.queryForList(
                "SELECT offer_id || ' ' || job || ' ' || reason || ' ' || external_system_id || ' ' || old_status || ' ' || old_ext_status || ' ' || " +
                    "old_client_price || ' ' || new_status || ' ' || (run_id IS NULL) FROM partner_offer_flip_log",
                String::class.java,
            ) shouldBe listOf("1 MMK_SYNC ROUTE_GONE 1 FREE 1 3456 UNAVAILABLE true")
            // the session's FREE copy of the hidden row can no longer be flushed back over it
            detached() shouldBe listOf(1L)
        }

        @Test
        fun `LOG counts what WRITE would hide and changes nothing`() {
            apply(MmkD3Mode.LOG) shouldBe 1
            statuses() shouldBe listOf("1 FREE 1", "2 FREE 1", "3 FREE 1")
            jdbc.queryForObject("SELECT count(*) FROM partner_offer_flip_log", Int::class.java) shouldBe 0
            detached() shouldBe emptyList()
        }

        @Test
        fun `OFF does nothing`() {
            apply(MmkD3Mode.OFF) shouldBe 0
            statuses() shouldBe listOf("1 FREE 1", "2 FREE 1", "3 FREE 1")
        }
    }
}
