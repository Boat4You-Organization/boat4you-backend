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
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Runs the real aggregation SQL of [CharterFactsComputeService], the real V9_61 / V9_67 migrations and the real search
 * matview (R__1_03) against a throw-away PostgreSQL 18 (prod major), on a fixture small enough to check every figure
 * by hand. See [CharterFactsTestDb].
 *
 * The run is pinned to TODAY = Saturday 26.9.2026 (the day of the audit), so the dates below are fixed:
 * W0 = today (the partial current month), W1 / W2 = 3.10. / 10.10. (month M = October), W3 = 7.11. (M+1 = November).
 *  - yachts 1-10 CATAMARAN at Marina Kaštela (l-1), 11-12 SAILING_YACHT at Marina Kastela (l-2, same place),
 *    13 at ACI Split (l-3); all in Croatia (c-54) and region r-5.
 *  - excluded on purpose: 14 inactive yacht, 15 inactive agency, 16 availability-blocked agency, 17 non-promoted
 *    country (NO), 19 a catamaran at l-1 whose every week is UNAVAILABLE (search never lists it, so it must not count
 *    as a boat, a model, a base or a week). 18 is a CUSTOM boat at l-1: listed by the search (activeBoats), no weeks.
 *  - B11: W0 is a late-season bucket (3,000 EUR for every catamaran) that must not become a month; yacht 20 prices its
 *    weeks at a 10 EUR placeholder, yacht 21 has a typo week (400 among 4,000s) - neither reaches a median.
 *  - B13: 22-24 at "Marina Baotić" (l-9) and 25-26 at "Trogir, Yachtclub Seget (Marina Baotić)" (l-8) are ONE base;
 *    "Marina Frapa" (l-6, Rogoznica, 28-32) is NOT "Marina Frapa Dubrovnik" (l-7, 170 km away, home of 27); gulet 33
 *    carries its own name "Aegean Alisa" as its model.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CharterFactsComputeServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        val TODAY: LocalDate = LocalDate.of(2026, 9, 26)
        val W0: LocalDate = TODAY
        val W1: LocalDate = LocalDate.of(2026, 10, 3)
        val W2: LocalDate = LocalDate.of(2026, 10, 10)
        val W3: LocalDate = LocalDate.of(2026, 11, 7)
        const val MONTH_M = "2026-10"
        const val MONTH_M1 = "2026-11"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: CharterFactsComputeService
    private lateinit var reader: CharterFactsReadService
    private val mapper = ObjectMapper()

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
        CharterFactsTestDb.create(jdbc)
        service = CharterFactsComputeService(jdbc, mapper, "BS,ES,FR,GD,GR,HR,IT,ME,MQ,SC,TR,VG")
        reader = CharterFactsReadService(jdbc, mapper)
        seed()
        CharterFactsTestDb.refreshSearchView(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    private fun seed() {
        jdbc.execute(
            """
            INSERT INTO country VALUES (54, 'HR'), (86, 'GR'), (160, 'NO');
            INSERT INTO location (id, name, country_code, city, lat, lon) VALUES
                (1, 'Marina Kaštela', 'HR', 'Kaštel Gomilica', 43.5516, 16.3627),
                (2, 'Marina Kastela', 'HR', NULL, NULL, NULL),
                (3, 'ACI Split', 'HR', NULL, NULL, NULL),
                (4, 'Lefkas', 'GR', NULL, NULL, NULL),
                (5, 'Oslo', 'NO', NULL, NULL, NULL),
                (6, 'Marina Frapa', 'HR', 'Rogoznica', 43.5310, 15.9640),
                (7, 'Marina Frapa Dubrovnik', 'HR', 'Dubrovnik', 42.6700, 18.0800),
                (8, 'Trogir, Yachtclub Seget (Marina Baotić)', 'HR', NULL, NULL, NULL),
                (9, 'Marina Baotić', 'HR', 'Seget Donji', NULL, NULL);
            -- r-5 Split region; r-7 claims a Croatian marina but is a Greek area -> the own-country guard drops it
            INSERT INTO region VALUES (5, 'Split region', 'HR'), (6, 'Dubrovnik region', 'HR'), (7, 'Greek area', 'GR'),
                                      (98, 'Ionian', NULL);
            INSERT INTO location_region VALUES (5, 1), (5, 2), (5, 3), (5, 6), (5, 8), (5, 9), (6, 7), (7, 1), (98, 4);
            INSERT INTO agency (id, active, availability_blocked) VALUES (1, true, false), (2, false, false), (3, true, true);
            INSERT INTO manufacturer VALUES (1, 'Lagoon'), (2, 'Bavaria'), (3, 'Beneteau'), (4, 'Gulet');
            INSERT INTO model VALUES (1, 'Lagoon 42', 1), (2, 'Lagoon 46', 1), (3, 'Cruiser 46', 2), (5, 'Oceanis 40', 3),
                                     (6, 'Aegean Alisa', 4);
            """.trimIndent(),
        )

        fun yacht(
            id: Int,
            type: String,
            model: Int?,
            home: Int?,
            build: Int? = null,
            deposit: Int? = null,
            currency: String? = null,
            name: String = "Yacht $id",
            agency: Int = 1,
            entry: String = "EXTERNAL",
            active: Boolean = true,
        ) {
            jdbc.update(
                "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, build_year, model_id, vessel_type, deposit, " +
                    "deposit_currency, location_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, name, agency, entry, active, build, model, type, deposit, currency, home,
            )
            jdbc.update("INSERT INTO yacht_charter_type (yacht_id, type) VALUES (?, 'BAREBOAT')", id)
        }

        // yachts 1-10: catamarans, build 2011..2020, deposit 1000*i EUR (yacht 10 in USD -> not comparable)
        for (i in 1..10) yacht(i, "CATAMARAN", if (i <= 6) 1 else 2, 1, 2010 + i, 1000 * i, if (i == 10) "USD" else "EUR")
        yacht(11, "SAILING_YACHT", 3, 2, 2015)
        yacht(12, "SAILING_YACHT", 3, 2, 2016, 0, "EUR")
        yacht(13, "SAILING_YACHT", 3, 3, 1800, 1, "EUR")
        yacht(14, "CATAMARAN", 1, 1, 2020, active = false)
        yacht(15, "CATAMARAN", 1, 1, 2020, agency = 2)
        yacht(16, "CATAMARAN", 1, 1, 2020, agency = 3)
        yacht(17, "CATAMARAN", 1, 5, 2020)
        yacht(18, "CATAMARAN", 1, 1, 2020, entry = "CUSTOM")
        jdbc.update("INSERT INTO custom_yacht_details (yacht_id, low_price) VALUES (18, 5600)")
        yacht(19, "CATAMARAN", 1, 1, 2020, 3000, "EUR")
        yacht(20, "SAILING_YACHT", 3, 3, 2015)
        yacht(21, "SAILING_YACHT", 3, 3, 2015)
        for (i in 22..24) yacht(i, "SAILING_YACHT", 3, 9, 2018)
        for (i in 25..26) yacht(i, "SAILING_YACHT", 3, 8, 2018)
        yacht(27, "SAILING_YACHT", 3, 7, 2018)
        for (i in 28..32) yacht(i, "SAILING_YACHT", 5, 6, 2019)
        yacht(33, "GULET", 6, 3, 1998, name = "Aegean Alisa")

        fun offer(
            yacht: Int,
            loc: Int,
            from: LocalDate,
            nights: Long,
            price: Int,
            status: String,
            locTo: Int = loc,
        ) = jdbc.update(
            "INSERT INTO offer (yacht_id, location_from, location_to, date_from, date_to, client_price, status, ext_base_price, " +
                "broker_commission, deposit) VALUES (?,?,?,?,?,?,?,?,0,0)",
            yacht, loc, locTo, java.sql.Date.valueOf(from), java.sql.Date.valueOf(from.plusDays(nights)), price, status, price,
        )

        // W0 (today, the partial current month): every catamaran at 3,000 - a month of its own before the fix
        for (i in 1..10) offer(i, 1, W0, 7, 3000, "FREE")
        // W1: catamarans 1..8 FREE at 1000*i, 9-10 UNAVAILABLE (week counts, price does not)
        for (i in 1..10) offer(i, 1, W1, 7, 1000 * i, if (i <= 8) "FREE" else "UNAVAILABLE")
        // same yacht-week, other product / one-way: collapsed into one week, cheapest visible price, round-trip base
        offer(1, 1, W1, 7, 20000, "FREE", locTo = 3)
        offer(1, 3, W1, 7, 800, "UNAVAILABLE")
        // W2: catamarans 1..10 OPTION at 2000 (priced, not free)
        for (i in 1..10) offer(i, 1, W2, 7, 2000, "OPTION")
        // W3 (month M+1): catamarans 1..8 FREE at 8000
        for (i in 1..8) offer(i, 1, W3, 7, 8000, "FREE")
        // sailing yachts 11-13: W1 FREE at 500
        offer(11, 2, W1, 7, 500, "FREE")
        offer(12, 2, W1, 7, 500, "FREE")
        offer(13, 3, W1, 7, 500, "FREE")
        // 20: a 10 EUR placeholder grid; 21: one typo week (400) among 4,000s
        for (d in listOf(W1, W2)) offer(20, 3, d, 7, 10, "FREE")
        offer(21, 3, W1, 7, 400, "FREE")
        for (d in listOf(W2, W3)) offer(21, 3, d, 7, 4000, "FREE")
        // the Baotić place (two rows) and Frapa Rogoznica, W1 + W3
        for (d in listOf(W1, W3)) {
            for (i in 22..24) offer(i, 9, d, 7, 1500, "FREE")
            for (i in 25..26) offer(i, 8, d, 7, 1500, "FREE")
            for (i in 28..32) offer(i, 6, d, 7, 1600, "FREE")
            offer(33, 3, d, 7, 5000, "FREE")
        }
        // not weekly / outside the window / cancelled -> ignored
        offer(1, 1, W1.plusDays(21), 14, 50000, "FREE")
        offer(1, 1, W1.plusDays(1), 3, 50, "FREE")
        offer(1, 1, TODAY.plusMonths(13), 7, 50, "FREE")
        offer(1, 1, TODAY.minusDays(14), 7, 50, "FREE")
        offer(2, 1, W1.plusDays(28), 7, 50, "CANCELLED")
        // excluded boats, all at l-1 in W1
        for (y in listOf(14, 15, 16)) offer(y, 1, W1, 7, 100, "FREE")
        offer(17, 5, W1, 7, 100, "FREE")
        // yacht 19: listed nowhere by search - every week UNAVAILABLE (withdrawn / owner-blocked)
        for (d in listOf(W1, W2, W3)) offer(19, 1, d, 7, 3000, "UNAVAILABLE")
        extra(19, "Skipper", 200, "PER_NIGHT")

        // Skipper: yachts 1-5 per night (160..200)*7 = 1120..1400, 6-10 per week 1600..2000
        for (i in 1..10) {
            if (i <= 5) extra(i, "Skipper", 150 + 10 * i, "PER_NIGHT") else extra(i, "Skipper", 1000 + 100 * i, "PER_WEEK")
        }
        extra(1, "Skipper + food", 100, "PER_NIGHT") // plain "Skipper" wins
        extra(2, "Skipper training practice", 900, "PER_BOOKING") // not a skipper for the week
        extra(3, "Skipper", 125, "PER_BOOKING") // 125/week = partner unit mistake -> out of bounds
        extra(4, "Skipper", 10, "PER_NIGHT_PERSON") // needs the party size -> unknown
        extra(5, "Skipper", 100, "PER_NIGHT", validTo = TODAY.minusDays(1)) // expired
        // Obligatory: Final cleaning 200 + Transit log 150 = 350 per boat; deposit-like / per-person excluded
        for (i in 1..10) {
            if (i != 2) extra(i, "Final cleaning", 200, "PER_BOOKING", obligatory = true)
            extra(i, "Transit log", 150, "PER_BOOKING", obligatory = true)
            extra(i, "Security deposit waiver", 400, "PER_BOOKING", obligatory = true)
            extra(i, "Tourist tax", 2, "PER_NIGHT_PERSON", obligatory = true)
        }
        // yacht 1: next season's cleaning price must not double-count today's
        extra(1, "Final cleaning", 250, "PER_BOOKING", obligatory = true, validFrom = TODAY.plusDays(200))
        // yacht 2: only an expired and a next-season cleaning -> the next-season one (260) counts: 410
        extra(2, "Final cleaning", 180, "PER_BOOKING", obligatory = true, validTo = TODAY.minusDays(1))
        extra(2, "Final cleaning", 260, "PER_BOOKING", obligatory = true, validFrom = TODAY.plusDays(100))
        // sailing yachts 11-12: 11 has an obligatory APA (percentage) -> its obligatory total is unknowable, left out;
        // 12 has only an optional extra -> 0 obligatory
        extra(11, "APA", 30, "PERCENTAGE", obligatory = true)
        extra(11, "Final cleaning", 100, "PER_BOOKING", obligatory = true)
        extra(12, "Snorkelling set", 50, "PER_BOOKING")
        // canonical Skipper extra (extras_id 1) whose name does not start with "Skipper": counts
        extra(12, "Professional skipper", 1500, "PER_WEEK", extrasId = 1)
    }

    private fun extra(
        yacht: Int,
        name: String,
        price: Int,
        unit: String,
        obligatory: Boolean = false,
        validFrom: LocalDate? = null,
        validTo: LocalDate? = null,
        extrasId: Int? = null,
    ) = jdbc.update(
        "INSERT INTO yacht_extras (yacht_id, name, price, unit, obligatory, valid_from, valid_to, extras_id) VALUES (?,?,?,?,?,?,?,?)",
        yacht, name, price, unit, obligatory, validFrom?.let { java.sql.Date.valueOf(it) }, validTo?.let { java.sql.Date.valueOf(it) },
        extrasId,
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
    fun `keys - promoted country, region and one place's rows with 10+ boats, types with 10+ boats`() {
        val summary = service.recompute(today = TODAY)
        summary.stored shouldBe true
        // 1-13, 20-26, 28-33 (27 has no offer; 14-19 excluded)
        summary.boats shouldBe 26L
        // c-54 + CAT + SAILING; r-5 the same; l-1 + CAT and l-2 + CAT (one place). Not: l-3 (4 boats), l-6 / l-8 / l-9
        // (5), r-7 (own-country guard), c-86 (no boats), c-160 (not promoted), GULET (1 boat).
        rows() shouldContainExactly
            listOf(
                "c-54" to null, "c-54" to "CATAMARAN", "c-54" to "SAILING_YACHT",
                "l-1" to null, "l-1" to "CATAMARAN",
                "l-2" to null, "l-2" to "CATAMARAN",
                "r-5" to null, "r-5" to "CATAMARAN", "r-5" to "SAILING_YACHT",
            )
    }

    @Test
    @Order(2)
    fun `B11 - full months only, placeholder and typo prices out, coverage rule, ranking`() {
        val f = facts("c-54")
        f["did"].asText() shouldBe "c-54"
        f["vesselType"].isNull shouldBe true
        f["computedAt"].asText() shouldNotBe ""
        f["windowFrom"].asText() shouldBe "2026-10-01"
        f["windowTo"].asText() shouldBe "2027-09-30"

        val m = f["priceByMonth"]
        // September 2026 (today's month, W0 at 3,000 for every catamaran) is not a month of its own
        m.map { it["month"].asText() } shouldContainExactly listOf(MONTH_M, MONTH_M1)
        // Trusted prices: yacht 20's 10 EUR weeks and yacht 21's grid (400 < 12 % of 4,000) never count. October has 24
        // priced boats, November 19 (>= 50 %): both are shown, priced over the SAME 19 boats (priced in both months) -
        // 1-8, Baotić 22-26, Frapa 28-32, gulet 33; 9-13 have no November price and stay out of both months.
        // M: W1 = 1000..8000 (1 collapsed: 1000, not 800 UNAVAILABLE / 20000) + 5 x 1500 + 5 x 1600 + 5000; W2 = 8 x 2000
        val pricesM = (1..8).map { it * 1000 } + List(5) { 1500 } + List(5) { 1600 } + 5000 + List(8) { 2000 }
        m[0]["offers"].asLong() shouldBe pricesM.size.toLong()
        m[0]["boats"].asLong() shouldBe 19L
        m[0]["p25"].asLong() shouldBe percentileCont(pricesM, 0.25)
        m[0]["median"].asLong() shouldBe percentileCont(pricesM, 0.5)
        m[0]["p75"].asLong() shouldBe percentileCont(pricesM, 0.75)
        val pricesM1 = List(8) { 8000 } + List(5) { 1500 } + List(5) { 1600 } + 5000
        m[1]["median"].asLong() shouldBe percentileCont(pricesM1, 0.5)
        m[1]["boats"].asLong() shouldBe 19L
        f["cheapestMonth"].asText() shouldBe MONTH_M1
        f["priciestMonth"].asText() shouldBe MONTH_M

        // M: W1 26 weeks (24 free), W2 12 weeks (2 free: 20, 21) -> 26/38; M+1: 20/20. September is not a month.
        val a = f["availableShareByMonth"]
        a.map { it["month"].asText() } shouldContainExactly listOf(MONTH_M, MONTH_M1)
        a[0]["weeks"].asLong() shouldBe 38L
        a[0]["share"].asDouble() shouldBe 0.684
        a[1]["share"].asDouble() shouldBe 1.0
        f["mostBookedMonth"].asText() shouldBe MONTH_M

        f["checkInDays"].map { it["day"].asText() to it["share"].asDouble() } shouldContainExactly
            listOf("SATURDAY" to 1.0)
    }

    @Test
    @Order(3)
    fun `B12 - activeBoats is the listing's count, the weekly-price sample is reported beside it`() {
        // listed: the 26 members + custom boat 18; not 19 (only UNAVAILABLE rows), 14-17 (search never lists them)
        facts("c-54")["activeBoats"].asLong() shouldBe 27L
        facts("c-54")["boatsWithWeeklyPrices"].asLong() shouldBe 26L
        facts("c-54", VesselType.CATAMARAN)["activeBoats"].asLong() shouldBe 11L
        facts("c-54", VesselType.CATAMARAN)["boatsWithWeeklyPrices"].asLong() shouldBe 10L
        facts("r-5")["activeBoats"].asLong() shouldBe 27L
        // l-1 = the Kaštela place (l-1 + l-2): 1-12 + custom 18
        facts("l-1")["activeBoats"].asLong() shouldBe 13L
        facts("l-2")["activeBoats"].asLong() shouldBe 13L
    }

    @Test
    @Order(4)
    fun `B13 - bases by physical place, one name per place, models without types, boat names or 1-boat rows`() {
        val f = facts("c-54")
        f["topBases"].map { Triple(it["locationId"].asLong(), it["name"].asText(), it["count"].asLong()) } shouldContainExactly
            listOf(
                // l-1 + l-2, named with the diacritics
                Triple(1L, "Marina Kaštela", 12L),
                // Rogoznica stays itself: "Marina Frapa Dubrovnik" is 170 km away
                Triple(6L, "Marina Frapa", 5L),
                // l-8 + l-9, named like the location list's merged row (the longer name)
                Triple(8L, "Trogir, Yachtclub Seget (Marina Baotić)", 5L),
                // ACI Split (4 boats) is below the 5-boat rule
            )
        f["topBases"][0]["did"].asText() shouldBe "l-1"
        // Lagoon 46 has 4 boats; "Aegean Alisa" is gulet 33's own name
        f["topModels"].map { it["model"].asText() to it["count"].asLong() } shouldContainExactly
            listOf("Cruiser 46" to 10L, "Lagoon 42" to 6L, "Oceanis 40" to 5L)
        f["boatTypeMix"].map { it["vesselType"].asText() to it["count"].asLong() } shouldContainExactly
            listOf("SAILING_YACHT" to 15L, "CATAMARAN" to 10L, "GULET" to 1L)
    }

    @Test
    @Order(5)
    fun `per-boat figures - skipper, obligatory extras, deposit, build year`() {
        val f = facts("c-54", VesselType.CATAMARAN)
        f.has("boatTypeMix") shouldBe false

        val skipper = listOf(1120, 1190, 1260, 1330, 1400, 1600, 1700, 1800, 1900, 2000)
        f["skipperWeekly"]["n"].asLong() shouldBe 10L
        f["skipperWeekly"]["median"].asLong() shouldBe percentileCont(skipper, 0.5)
        f["skipperWeekly"]["p25"].asLong() shouldBe percentileCont(skipper, 0.25)
        f["skipperWeekly"]["p75"].asLong() shouldBe percentileCont(skipper, 0.75)

        // all types: + yacht 12's "Professional skipper" (extras_id 1) 1500/week; yacht 19 (never bookable) not counted
        val skipperAll = skipper + 1500
        facts("c-54")["skipperWeekly"]["n"].asLong() shouldBe 11L
        facts("c-54")["skipperWeekly"]["median"].asLong() shouldBe percentileCont(skipperAll, 0.5)

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

        // all types: yacht 12 counts as 0 (median of 0 + 9 x 350 + 410 is still 350); 11 (APA) and the boats with no
        // extras rows at all are left out instead of counting as "0 obligatory"
        facts("c-54")["obligatoryExtrasWeekly"]["n"].asLong() shouldBe 11L
        facts("c-54")["obligatoryExtrasWeekly"]["median"].asLong() shouldBe 350L
        // yacht 13's 1 EUR deposit is a placeholder, 12's 0 is none -> still the 9 catamarans
        facts("c-54")["deposit"]["n"].asLong() shouldBe 9L
        facts("c-54")["deposit"]["min"].asLong() shouldBe 1000L
    }

    @Test
    @Order(6)
    fun `marina rows - one place shares the boats, no bases list`() {
        val l1 = facts("l-1")
        val l2 = facts("l-2")
        l1["boatsWithWeeklyPrices"].asLong() shouldBe 12L
        l2["boatsWithWeeklyPrices"].asLong() shouldBe 12L
        l1.has("topBases") shouldBe false
        facts("r-5")["boatsWithWeeklyPrices"].asLong() shouldBe 26L
        reader.find("l-3", null) shouldBe null
        reader.find("l-7", null) shouldBe null
        reader.find("c-86", null) shouldBe null
        reader.find("c-54", VesselType.GULET) shouldBe null
    }

    @Test
    @Order(7)
    fun `re-run replaces the snapshot, a collapsed input keeps yesterday's facts`() {
        val first = rows()
        service.recompute(today = TODAY).stored shouldBe true
        rows() shouldContainExactly first

        // an incident empties most of the offer table: 10 rows would become 4 -> refused, old rows stay
        jdbc.update("DELETE FROM offer WHERE yacht_id BETWEEN 3 AND 12")
        CharterFactsTestDb.refreshSearchView(jdbc)
        val summary = service.recompute(today = TODAY)
        summary.stored shouldBe false
        rows() shouldContainExactlyInAnyOrder first
        // the connection went back to the pool clean (SET LOCAL + ON COMMIT DROP)
        jdbc.queryForObject("SHOW statement_timeout", String::class.java) shouldBe "0"
        jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('cf_week', 'cf_place', 'cf_key_month')", Int::class.java) shouldBe 0
        val before = service.latestComputedAt()!!

        // ops: the shrink is legitimate -> force replaces
        val forced = service.recompute(force = true, today = TODAY)
        forced.stored shouldBe true
        rows() shouldContainExactly listOf("c-54" to null, "c-54" to "SAILING_YACHT", "r-5" to null, "r-5" to "SAILING_YACHT")
        // 1, 2, 13, 20-26, 28-33 + custom 18
        facts("c-54")["activeBoats"].asLong() shouldBe 17L
        (service.latestComputedAt()!! >= before) shouldBe true

        // force never overrides the empty-result guard
        jdbc.update("DELETE FROM offer")
        service.recompute(force = true, today = TODAY).stored shouldBe false
        rows() shouldContainExactly listOf("c-54" to null, "c-54" to "SAILING_YACHT", "r-5" to null, "r-5" to "SAILING_YACHT")
    }
}
