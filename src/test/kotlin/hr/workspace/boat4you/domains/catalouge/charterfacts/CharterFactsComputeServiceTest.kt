package hr.workspace.boat4you.domains.catalouge.charterfacts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Runs the real aggregation SQL of [CharterFactsComputeService] and the real V9_61 migration against a throw-away
 * PostgreSQL, on a fixture small enough to check every figure by hand. Same approach as YachtSearchViewRefresherTest:
 * no Spring context / Flyway (the versioned history cannot be replayed on an empty database), a minimal schema with
 * exactly the columns the job reads.
 *
 * Fixture (dates relative to the container's CURRENT_DATE): month M = next calendar month, W1 / W2 = its first two
 * Saturdays, W3 = first Saturday of M+1.
 *  - yachts 1-10 CATAMARAN at Marina Kaštela (l-1), 11-12 SAILING_YACHT at Marina Kastela (l-2, same-name sibling),
 *    13 SAILING_YACHT at ACI Split (l-3); all in Croatia (c-54) and region r-5.
 *  - excluded on purpose: 14 inactive yacht, 15 inactive agency, 16 availability-blocked agency, 17 non-promoted
 *    country (NO), 18 CUSTOM entry.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CharterFactsComputeServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        val MINIMAL_SCHEMA =
            """
            CREATE TABLE country (id int PRIMARY KEY, code2 varchar(2) NOT NULL);
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_code varchar(2) NOT NULL);
            CREATE TABLE region (id int PRIMARY KEY, name varchar(100), country_code varchar(2));
            CREATE TABLE location_region (region_id int NOT NULL, location_id bigint NOT NULL);
            CREATE TABLE agency (id bigint PRIMARY KEY, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, agency_id bigint, entry_type varchar(31) NOT NULL,
                                sys_active boolean NOT NULL DEFAULT true, build_year smallint, model_id bigint,
                                vessel_type varchar(31) NOT NULL, deposit numeric, deposit_currency varchar(20));
            CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, location_from bigint NOT NULL,
                                location_to bigint NOT NULL, date_from date NOT NULL, date_to date NOT NULL,
                                client_price numeric NOT NULL, status varchar(31) NOT NULL);
            CREATE TABLE yacht_extras (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, name varchar, price numeric NOT NULL,
                                       unit varchar(31) NOT NULL, obligatory boolean NOT NULL, valid_from date, valid_to date);
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: CharterFactsComputeService
    private lateinit var reader: CharterFactsReadService
    private val mapper = ObjectMapper()

    private lateinit var today: LocalDate
    private lateinit var w1: LocalDate
    private lateinit var w2: LocalDate
    private lateinit var w3: LocalDate
    private lateinit var monthM: String
    private lateinit var monthM1: String

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
        jdbc.execute(MINIMAL_SCHEMA)
        // The real migration, in a transaction like Flyway (it uses SET LOCAL).
        val migration = ClassPathResource("db/migration/V9_61__charter_facts.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $migration; COMMIT;")
        service = CharterFactsComputeService(jdbc, mapper, "BS,ES,FR,GD,GR,HR,IT,ME,MQ,SC,TR,VG")
        reader = CharterFactsReadService(jdbc, mapper)

        today = jdbc.queryForObject("SELECT CURRENT_DATE", java.sql.Date::class.java)!!.toLocalDate()
        val m = today.plusMonths(1).withDayOfMonth(1)
        w1 = m.with(TemporalAdjusters.firstInMonth(DayOfWeek.SATURDAY))
        w2 = w1.plusDays(7)
        w3 = m.plusMonths(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SATURDAY))
        monthM = m.toString().substring(0, 7)
        monthM1 = m.plusMonths(1).toString().substring(0, 7)
        seed()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    private fun seed() {
        jdbc.execute(
            """
            INSERT INTO country VALUES (54, 'HR'), (86, 'GR'), (160, 'NO');
            INSERT INTO location VALUES (1, 'Marina Kaštela', 'HR'), (2, 'Marina Kastela', 'HR'), (3, 'ACI Split', 'HR'),
                                        (4, 'Lefkas', 'GR'), (5, 'Oslo', 'NO');
            -- r-5 Split region; r-7 claims a Croatian marina but is a Greek area -> the own-country guard drops it
            INSERT INTO region VALUES (5, 'Split region', 'HR'), (7, 'Greek area', 'GR'), (98, 'Ionian', NULL);
            INSERT INTO location_region VALUES (5, 1), (5, 2), (5, 3), (7, 1), (98, 4);
            INSERT INTO agency VALUES (1, true, false), (2, false, false), (3, true, true);
            INSERT INTO manufacturer VALUES (1, 'Lagoon'), (2, 'Bavaria');
            INSERT INTO model VALUES (1, 'Lagoon 42', 1), (2, 'Lagoon 46', 1), (3, 'Cruiser 46', 2);
            """.trimIndent(),
        )
        // yachts 1-10: catamarans, build 2011..2020, deposit 1000*i EUR (yacht 10 in USD -> not comparable)
        for (i in 1..10) {
            jdbc.update(
                "INSERT INTO yacht VALUES (?, 1, 'EXTERNAL', true, ?, ?, 'CATAMARAN', ?, ?)",
                i, 2010 + i, if (i <= 6) 1 else 2, 1000 * i, if (i == 10) "USD" else "EUR",
            )
        }
        jdbc.execute(
            """
            INSERT INTO yacht VALUES (11, 1, 'EXTERNAL', true, 2015, 3, 'SAILING_YACHT', NULL, NULL),
                                     (12, 1, 'EXTERNAL', true, 2016, 3, 'SAILING_YACHT', 0, 'EUR'),
                                     (13, 1, 'EXTERNAL', true, 1800, 3, 'SAILING_YACHT', 1, 'EUR'),
                                     (14, 1, 'EXTERNAL', false, 2020, 1, 'CATAMARAN', NULL, NULL),
                                     (15, 2, 'EXTERNAL', true, 2020, 1, 'CATAMARAN', NULL, NULL),
                                     (16, 3, 'EXTERNAL', true, 2020, 1, 'CATAMARAN', NULL, NULL),
                                     (17, 1, 'EXTERNAL', true, 2020, 1, 'CATAMARAN', NULL, NULL),
                                     (18, 1, 'CUSTOM', true, 2020, 1, 'CATAMARAN', NULL, NULL);
            """.trimIndent(),
        )

        fun offer(
            yacht: Int,
            loc: Int,
            from: LocalDate,
            nights: Long,
            price: Int,
            status: String,
            locTo: Int = loc,
        ) = jdbc.update(
            "INSERT INTO offer (yacht_id, location_from, location_to, date_from, date_to, client_price, status) VALUES (?,?,?,?,?,?,?)",
            yacht, loc, locTo, java.sql.Date.valueOf(from), java.sql.Date.valueOf(from.plusDays(nights)), price, status,
        )

        // W1: catamarans 1..8 FREE at 1000*i, 9-10 UNAVAILABLE (week counts, price does not)
        for (i in 1..10) offer(i, 1, w1, 7, 1000 * i, if (i <= 8) "FREE" else "UNAVAILABLE")
        // same yacht-week, other product / one-way: collapsed into one week, cheapest visible price, round-trip base
        offer(1, 1, w1, 7, 20000, "FREE", locTo = 3)
        offer(1, 3, w1, 7, 800, "UNAVAILABLE")
        // W2: catamarans 1..10 OPTION at 2000 (priced, not free)
        for (i in 1..10) offer(i, 1, w2, 7, 2000, "OPTION")
        // W3 (month M+1): catamarans 1..5 FREE at 9000
        for (i in 1..5) offer(i, 1, w3, 7, 9000, "FREE")
        // sailing yachts: W1 FREE at 500
        offer(11, 2, w1, 7, 500, "FREE")
        offer(12, 2, w1, 7, 500, "FREE")
        offer(13, 3, w1, 7, 500, "FREE")
        // not weekly / outside the window / cancelled -> ignored
        offer(1, 1, w1.plusDays(21), 14, 50000, "FREE")
        offer(1, 1, w1.plusDays(1), 3, 50, "FREE")
        offer(1, 1, today.plusMonths(13), 7, 50, "FREE")
        offer(1, 1, today.minusDays(14), 7, 50, "FREE")
        offer(2, 1, w1.plusDays(28), 7, 50, "CANCELLED")
        // excluded boats, all at l-1 in W1
        for (y in listOf(14, 15, 16, 18)) offer(y, 1, w1, 7, 100, "FREE")
        offer(17, 5, w1, 7, 100, "FREE")

        // Skipper: yachts 1-5 per night (160..200)*7 = 1120..1400, 6-10 per week 1600..2000
        for (i in 1..10) {
            if (i <= 5) extra(i, "Skipper", 150 + 10 * i, "PER_NIGHT") else extra(i, "Skipper", 1000 + 100 * i, "PER_WEEK")
        }
        extra(1, "Skipper + food", 100, "PER_NIGHT") // plain "Skipper" wins
        extra(2, "Skipper training practice", 900, "PER_BOOKING") // not a skipper for the week
        extra(3, "Skipper", 125, "PER_BOOKING") // 125/week = partner unit mistake -> out of bounds
        extra(4, "Skipper", 10, "PER_NIGHT_PERSON") // needs the party size -> unknown
        extra(5, "Skipper", 100, "PER_NIGHT", validTo = today.minusDays(1)) // expired
        // Obligatory: Final cleaning 200 + Transit log 150 = 350 per boat; deposit-like / per-person excluded
        for (i in 1..10) {
            if (i != 2) extra(i, "Final cleaning", 200, "PER_BOOKING", obligatory = true)
            extra(i, "Transit log", 150, "PER_BOOKING", obligatory = true)
            extra(i, "Security deposit waiver", 400, "PER_BOOKING", obligatory = true)
            extra(i, "Tourist tax", 2, "PER_NIGHT_PERSON", obligatory = true)
        }
        // yacht 1: next season's cleaning price must not double-count today's
        extra(1, "Final cleaning", 250, "PER_BOOKING", obligatory = true, validFrom = today.plusDays(200))
        // yacht 2: only an expired and a next-season cleaning -> the next-season one (260) counts: 410
        extra(2, "Final cleaning", 180, "PER_BOOKING", obligatory = true, validTo = today.minusDays(1))
        extra(2, "Final cleaning", 260, "PER_BOOKING", obligatory = true, validFrom = today.plusDays(100))
        // sailing yachts 11-12: 11 has an obligatory APA (percentage) -> its obligatory total is unknowable, left out;
        // 12 has only an optional extra -> 0 obligatory
        extra(11, "APA", 30, "PERCENTAGE", obligatory = true)
        extra(11, "Final cleaning", 100, "PER_BOOKING", obligatory = true)
        extra(12, "Snorkelling set", 50, "PER_BOOKING")
    }

    private fun extra(
        yacht: Int,
        name: String,
        price: Int,
        unit: String,
        obligatory: Boolean = false,
        validFrom: LocalDate? = null,
        validTo: LocalDate? = null,
    ) = jdbc.update(
        "INSERT INTO yacht_extras (yacht_id, name, price, unit, obligatory, valid_from, valid_to) VALUES (?,?,?,?,?,?,?)",
        yacht, name, price, unit, obligatory, validFrom?.let { java.sql.Date.valueOf(it) }, validTo?.let { java.sql.Date.valueOf(it) },
    )

    private fun rows(): List<Pair<String, String?>> =
        jdbc.query("SELECT did, vessel_type FROM charter_facts ORDER BY did, vessel_type NULLS FIRST") { rs, _ ->
            rs.getString(1) to rs.getString(2)
        }

    private fun facts(
        did: String,
        type: VesselType? = null,
    ): JsonNode = reader.find(did, type)!!

    /** Postgres percentile_cont: linear interpolation at p * (n - 1). */
    private fun percentileCont(
        values: List<Int>,
        p: Double,
    ): Long {
        val s = values.sorted()
        val pos = p * (s.size - 1)
        val lo = s[pos.toInt()]
        val hi = s[minOf(pos.toInt() + 1, s.size - 1)]
        return BigDecimal(lo + (hi - lo) * (pos - pos.toInt())).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    @Test
    @Order(1)
    fun `keys - promoted country, region and sibling marinas with 10+ boats, types with 10+ boats`() {
        val summary = service.recompute()
        summary.stored shouldBe true
        summary.boats shouldBe 13L
        // c-54 + CAT; r-5 + CAT; l-1 + CAT; l-2 + CAT (same sibling group). Not: l-3 (1 boat), r-7 (guard),
        // c-86 (no boats), c-160 (not promoted), SAILING_YACHT (3 boats).
        rows() shouldContainExactly
            listOf(
                "c-54" to null, "c-54" to "CATAMARAN",
                "l-1" to null, "l-1" to "CATAMARAN",
                "l-2" to null, "l-2" to "CATAMARAN",
                "r-5" to null, "r-5" to "CATAMARAN",
            )
    }

    @Test
    @Order(2)
    fun `country all types - boats, months, availability, bases, models, type mix`() {
        val f = facts("c-54")
        f["did"].asText() shouldBe "c-54"
        f["vesselType"].isNull shouldBe true
        f["computedAt"].asText() shouldNotBe ""
        f["activeBoats"].asLong() shouldBe 13L
        f["windowFrom"].asText() shouldBe today.toString()

        val m = f["priceByMonth"]
        m.map { it["month"].asText() } shouldContainExactly listOf(monthM, monthM1)
        // M: W1 = 1000..8000 (1 collapsed: 1000, not 800 UNAVAILABLE / 20000) + 3 x 500, W2 = 10 x 2000
        val pricesM = (1..8).map { it * 1000 } + listOf(500, 500, 500) + List(10) { 2000 }
        m[0]["offers"].asLong() shouldBe pricesM.size.toLong()
        m[0]["p25"].asLong() shouldBe percentileCont(pricesM, 0.25)
        m[0]["median"].asLong() shouldBe percentileCont(pricesM, 0.5)
        m[0]["p75"].asLong() shouldBe percentileCont(pricesM, 0.75)
        m[1]["median"].asLong() shouldBe 9000L
        f["cheapestMonth"].asText() shouldBe monthM
        f["priciestMonth"].asText() shouldBe monthM1

        // M: W1 13 weeks (11 free), W2 10 weeks (0 free) -> 11/23; M+1: 5/5
        val a = f["availableShareByMonth"]
        a[0]["weeks"].asLong() shouldBe 23L
        a[0]["share"].asDouble() shouldBe 0.478
        a[1]["share"].asDouble() shouldBe 1.0
        f["mostBookedMonth"].asText() shouldBe monthM

        f["checkInDays"].map { it["day"].asText() to it["share"].asDouble() } shouldContainExactly
            listOf("SATURDAY" to 1.0)

        f["topBases"].map { Triple(it["locationId"].asLong(), it["count"].asLong(), it["did"].asText()) } shouldContainExactly
            listOf(Triple(1L, 12L, "l-1"), Triple(3L, 1L, "l-3"))
        f["topBases"][0]["name"].asText() shouldBe "Marina Kaštela"
        f["topModels"].map { it["model"].asText() to it["count"].asLong() } shouldContainExactly
            listOf("Lagoon 42" to 6L, "Lagoon 46" to 4L, "Cruiser 46" to 3L)
        f["boatTypeMix"].map { it["vesselType"].asText() to it["count"].asLong() } shouldContainExactly
            listOf("CATAMARAN" to 10L, "SAILING_YACHT" to 3L)
    }

    @Test
    @Order(3)
    fun `per-boat figures - skipper, obligatory extras, deposit, build year`() {
        val f = facts("c-54", VesselType.CATAMARAN)
        f["activeBoats"].asLong() shouldBe 10L
        f.has("boatTypeMix") shouldBe false

        val skipper = listOf(1120, 1190, 1260, 1330, 1400, 1600, 1700, 1800, 1900, 2000)
        f["skipperWeekly"]["n"].asLong() shouldBe 10L
        f["skipperWeekly"]["median"].asLong() shouldBe percentileCont(skipper, 0.5)
        f["skipperWeekly"]["p25"].asLong() shouldBe percentileCont(skipper, 0.25)
        f["skipperWeekly"]["p75"].asLong() shouldBe percentileCont(skipper, 0.75)

        // 9 boats x 350, yacht 2 = 260 + 150 = 410
        f["obligatoryExtrasWeekly"]["median"].asLong() shouldBe 350L
        f["obligatoryExtrasWeekly"]["n"].asLong() shouldBe 10L

        // 1000..9000 EUR (yacht 10 is USD; yacht 12 has 0 = none)
        f["deposit"]["min"].asLong() shouldBe 1000L
        f["deposit"]["median"].asLong() shouldBe 5000L
        f["deposit"]["max"].asLong() shouldBe 9000L
        f["deposit"]["n"].asLong() shouldBe 9L

        // 2011..2020 -> percentile_disc(0.5) = 2015
        f["medianBuildYear"].asInt() shouldBe 2015

        // all types: yacht 12 counts as 0 (median of 0 + 9 x 350 + 410 is still 350); 11 (APA) and 13 (no extras
        // rows at all) are left out instead of counting as "0 obligatory"
        facts("c-54")["obligatoryExtrasWeekly"]["n"].asLong() shouldBe 11L
        facts("c-54")["obligatoryExtrasWeekly"]["median"].asLong() shouldBe 350L
        // yacht 13's 1 EUR deposit is a placeholder, 12's 0 is none -> still the 9 catamarans
        facts("c-54")["deposit"]["n"].asLong() shouldBe 9L
        facts("c-54")["deposit"]["min"].asLong() shouldBe 1000L
    }

    @Test
    @Order(4)
    fun `marina rows - siblings share the boats, no bases list`() {
        val l1 = facts("l-1")
        val l2 = facts("l-2")
        l1["activeBoats"].asLong() shouldBe 12L
        l2["activeBoats"].asLong() shouldBe 12L
        l1.has("topBases") shouldBe false
        facts("r-5")["activeBoats"].asLong() shouldBe 13L
        reader.find("l-3", null) shouldBe null
        reader.find("c-86", null) shouldBe null
        reader.find("c-54", VesselType.SAILING_YACHT) shouldBe null
    }

    @Test
    @Order(5)
    fun `re-run replaces the snapshot, a collapsed input keeps yesterday's facts`() {
        val first = rows()
        service.recompute().stored shouldBe true
        rows() shouldContainExactly first

        // an incident empties most of the offer table: 8 rows would become 2 -> refused, old rows stay
        jdbc.update("DELETE FROM offer WHERE yacht_id BETWEEN 3 AND 12")
        val summary = service.recompute()
        summary.stored shouldBe false
        rows() shouldContainExactlyInAnyOrder first
        // the connection went back to the pool clean (SET LOCAL + ON COMMIT DROP)
        jdbc.queryForObject("SHOW statement_timeout", String::class.java) shouldBe "0"
        jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname = 'cf_week'", Int::class.java) shouldBe 0
    }
}
