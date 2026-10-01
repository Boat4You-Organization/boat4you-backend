package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
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
import java.time.LocalDate

/**
 * 26.9.2026 audit B17, review: which copy of a boat imported twice the listings show (V9_69 yacht_listing_twin). The
 * first rule - most future offers - moved with every booking and every passing week, so the shown copy, its sitemap
 * entry and the boat page's canonical flipped between the two ids (replay on a real catalogue: 10 of 496 pairs within
 * a month). The copy shown is now fixed: a copy with a week left to sell, then a directly bookable one, then the older
 * id - and only a copy the undated listing can show at all. Real V9_69 on PostgreSQL.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtListingTwinTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val TODAY: LocalDate = LocalDate.now()

        private val SCHEMA =
            """
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255), country_code varchar(2));
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name text);
            CREATE TABLE model (id bigint PRIMARY KEY, name text, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name text, agency_id bigint, entry_type text NOT NULL,
                                sys_active boolean NOT NULL DEFAULT true, location_id bigint, build_year integer,
                                model_id bigint, vessel_type text, length numeric);
            CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL,
                                date_to date NOT NULL, status text NOT NULL);
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate

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
        jdbc.execute(SCHEMA)
        ListingTwinTestSupport.createAndRefresh(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute(
            """
            TRUNCATE offer, yacht, agency, location, model, manufacturer;
            TRUNCATE yacht_twin_manual_pair;
            INSERT INTO location (id, name, country_code) VALUES (1, 'Marina Frapa Dubrovnik', 'HR'),
                                                                 (2, 'Trogir, Yachtclub Seget (Marina Baotić)', 'HR'),
                                                                 (3, 'Marina Baotic | Seget Donji', 'HR');
            INSERT INTO agency (id, name, inquiry_only) VALUES (1, 'Owner', false), (2, 'Broker', false), (3, 'Third', false),
                                                               (4, 'Inquiry only', true);
            """.trimIndent(),
        )
    }

    private fun yacht(
        id: Int,
        agency: Int,
        length: Double = 11.55,
        optionApproval: Boolean = false,
        location: Int = 1,
    ) = jdbc.update(
        "INSERT INTO yacht (id, name, agency_id, entry_type, location_id, build_year, vessel_type, length, option_approval) " +
            "VALUES (?, 'Pampero', ?, 'EXTERNAL', ?, 2018, 'CATAMARAN', ?, ?)",
        id, agency, location, length, optionApproval,
    )

    private fun weeks(
        yacht: Int,
        count: Int,
        status: String = "FREE",
        firstWeek: Long = 2,
    ) = (0 until count).forEach { i ->
        val from = TODAY.plusWeeks(firstWeek + i)
        jdbc.update(
            "INSERT INTO offer (yacht_id, date_from, date_to, status) VALUES (?, ?, ?, ?)",
            yacht, java.sql.Date.valueOf(from), java.sql.Date.valueOf(from.plusDays(7)), status,
        )
    }

    /** hidden copy -> copy shown, after a refresh like YachtSearchViewRefresher's. */
    private fun twins(): Map<Long, Long> {
        ListingTwinTestSupport.refresh(jdbc)
        return jdbc
            .query("SELECT yacht_id, canonical_yacht_id FROM yacht_listing_twin") { rs, _ -> rs.getLong(1) to rs.getLong(2) }
            .toMap()
    }

    @Test
    fun `offer counts never move the copy shown - the older id stays canonical`() {
        yacht(201, agency = 1)
        yacht(202, agency = 2)
        weeks(201, 1)
        weeks(202, 1)
        twins() shouldBe mapOf(202L to 201L)
        // the newer copy publishes many more weeks, the older one sells its only week to an option
        weeks(202, 8, firstWeek = 3)
        jdbc.update("UPDATE offer SET status = 'OPTION' WHERE yacht_id = 201")
        twins() shouldBe mapOf(202L to 201L)
        // the older copy's only week is booked: no week left to sell on 201, so the copy that can still be booked is
        // shown - the one flip the rule allows, and only on such a change of state
        jdbc.update("UPDATE offer SET status = 'RESERVED' WHERE yacht_id = 201")
        twins() shouldBe mapOf(201L to 202L)
    }

    @Test
    fun `a directly bookable copy before an inquiry-only one, whatever the ids`() {
        yacht(211, agency = 4)
        yacht(212, agency = 1)
        yacht(213, agency = 2, optionApproval = true)
        listOf(211, 212, 213).forEach { weeks(it, 2) }
        twins() shouldBe mapOf(211L to 212L, 213L to 212L)
    }

    @Test
    fun `only a copy the listing can show is ever the copy shown`() {
        // 221 has only past weeks; 222 only UNAVAILABLE ones: neither can be listed, so neither hides 223
        yacht(221, agency = 1)
        yacht(222, agency = 2)
        yacht(223, agency = 3)
        jdbc.update(
            "INSERT INTO offer (yacht_id, date_from, date_to, status) VALUES (221, ?, ?, 'FREE')",
            java.sql.Date.valueOf(TODAY.minusWeeks(3)), java.sql.Date.valueOf(TODAY.minusWeeks(2)),
        )
        weeks(222, 2, status = "UNAVAILABLE")
        weeks(223, 1)
        twins() shouldBe mapOf(221L to 223L, 222L to 223L)
        // no copy can be listed: nothing is hidden
        jdbc.update("DELETE FROM offer WHERE yacht_id = 223")
        twins() shouldBe emptyMap()
    }

    @Test
    fun `a chain of near matches names the copy that is shown`() {
        // 231 ~ 232 (0.4 m) and 232 ~ 233 (0.4 m), but 231 and 233 are 0.8 m apart: 233 still points at 231
        yacht(231, agency = 1, length = 12.0)
        yacht(232, agency = 2, length = 12.4)
        yacht(233, agency = 3, length = 12.8)
        listOf(231, 232, 233).forEach { weeks(it, 1) }
        twins() shouldBe mapOf(232L to 231L, 233L to 231L)
    }

    @Test
    fun `a hand-verified pair the name rule cannot match shows one copy by the same stable rule`() {
        // 1.10.2026, SEO regression SM4: NauSys and MMK spell one marina differently ("Trogir, Yachtclub Seget (Marina
        // Baotić)" / "Marina Baotic"), so the rule never paired Desafinado 481 / 13163 and every listing and sitemap
        // carried both - while the boat page (twin-canonical manual group) shows one of them for either URL.
        yacht(241, agency = 1, location = 2)
        yacht(242, agency = 2, location = 3)
        listOf(241, 242).forEach { weeks(it, 2) }
        twins() shouldBe emptyMap()
        jdbc.update("INSERT INTO yacht_twin_manual_pair (yacht_id, twin_yacht_id, note) VALUES (242, 241, 'test')")
        twins() shouldBe mapOf(242L to 241L)
        // the same rule as every other pair picks the copy shown: the older copy sold out -> the other one is shown
        jdbc.update("UPDATE offer SET status = 'RESERVED' WHERE yacht_id = 241")
        twins() shouldBe mapOf(241L to 242L)
    }
}
