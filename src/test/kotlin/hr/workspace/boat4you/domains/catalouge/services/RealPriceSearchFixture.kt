package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The verification shapes of the real-price search rule (DESIGN.md WS-A tests 1-12) as one catalogue, on the real
 * R__1_03 matview, V9_59 (mmk_free_week_strike) and V9_82 (partner_period_check). It only uses API that existed
 * before WS-A, so the same fixture produced the LEGACY golden file (src/test/resources/realprice/legacy-golden.txt)
 * on the base commit a9577a1: [YachtSearchRealPriceTest] proves that LEGACY + floor OFF answer exactly as before.
 *
 * Periods (far from today, a month apart, so no request reaches another one's rows):
 *  - [S] Sat 5.7.2031, 7 nights: exact vs shifted, longer / shorter blocks, evidence, hull, floor, A2;
 *  - [W] Wed 6.8.2031, 7 nights: the cancelled-reservation shape and the +-3 rule on a Wednesday;
 *  - [T] Sat 6.9.2031, 14 nights: tiling (GOLDFINCH, gulet with two charter types, two routes, filters);
 *  - [Q] Sat 4.10.2031, 3 nights: the shift is min(3, N - 1) = 2.
 */
object RealPriceSearchFixture {
    val S: LocalDate = LocalDate.of(2031, 7, 5)
    val W: LocalDate = LocalDate.of(2031, 8, 6)
    val T: LocalDate = LocalDate.of(2031, 9, 6)
    val Q: LocalDate = LocalDate.of(2031, 10, 4)

    /** The custom (admin-managed) yacht without a low price. */
    const val CUSTOM = 132L

    /** The placeholder-priced boat (11 EUR weeks and a NULL price): the Angelmiles shape. */
    const val PLACEHOLDER = 130L

    const val OPTION_BOAT = 150L

    val SCHEMA =
        """
        CREATE TABLE country (id int PRIMARY KEY, name varchar(100), code2 varchar(2) NOT NULL, code3 varchar(3) NOT NULL,
                              continent varchar(15) NOT NULL);
        CREATE TABLE region (id serial PRIMARY KEY, name varchar(100), country_id int, country_code varchar(2));
        CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_code varchar(2) NOT NULL,
                               lat numeric, lon numeric, country_id int NOT NULL, city varchar(100),
                               inland boolean NOT NULL DEFAULT false, display_name text);
        CREATE TABLE location_region (region_id int NOT NULL, location_id bigint NOT NULL);
        CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                             availability_blocked boolean NOT NULL DEFAULT false, recommended boolean);
        CREATE TABLE manufacturer (id bigint PRIMARY KEY, name text);
        CREATE TABLE model (id bigint PRIMARY KEY, name text, manufacturer_id bigint);
        CREATE TABLE yacht (id bigint PRIMARY KEY, name text, agency_id bigint, entry_type text NOT NULL,
                            sys_active boolean NOT NULL DEFAULT true, location_id bigint, build_year integer,
                            model_id bigint, vessel_type text, mainsail_type text, max_persons smallint,
                            cabins smallint, berths smallint, length numeric, wc smallint, engine_power numeric,
                            main_image_id bigint, deposit numeric);
        CREATE TABLE yacht_charter_type (id bigint PRIMARY KEY, yacht_id bigint, type text);
        CREATE TABLE custom_yacht_details (yacht_id bigint PRIMARY KEY, low_price numeric);
        CREATE TABLE offer (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, location_from bigint NOT NULL,
                            location_to bigint, client_price numeric, ext_base_price numeric,
                            broker_commission numeric, date_from date NOT NULL, date_to date NOT NULL,
                            deposit numeric, status text NOT NULL, ext_status varchar(50));
        CREATE TABLE external_reservations (id bigint PRIMARY KEY, yacht_id bigint, date_from date NOT NULL,
                            date_to date NOT NULL, status text NOT NULL, option_expiration timestamp);
        CREATE TABLE equipment (id bigint PRIMARY KEY, name text, label_code text, category text,
                            match_keys text, filter_order smallint);
        CREATE TABLE yacht_equipment (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, equipment_id bigint,
                            name text, external_id bigint, highlight boolean NOT NULL DEFAULT false,
                            quantity numeric, comment text);
        CREATE TABLE yacht_content_modified (yacht_id bigint PRIMARY KEY, modified_at timestamptz NOT NULL);
        CREATE TABLE reservation_extras (id bigint PRIMARY KEY);
        """.trimIndent()

    private class Seed {
        val sql = StringBuilder()
        private var offerId = 0
        private var reservationId = 0

        fun line(s: String) = sql.appendLine(s)

        fun yacht(
            id: Long,
            vessel: String = "SAILING_YACHT",
            types: List<String> = listOf("BAREBOAT"),
            entry: String = "EXTERNAL",
        ) {
            line(
                "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id, cabins, build_year, length) " +
                    "VALUES ($id, 'Yacht $id', 1, '$entry', true, '$vessel', 1, 3, 2015, 12);",
            )
            types.forEachIndexed { i, type ->
                line("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES (${id * 10 + i}, $id, '$type');")
            }
        }

        @Suppress("LongParameterList") // a seed row: every call names its arguments
        fun offer(
            yacht: Long,
            from: LocalDate,
            nights: Int,
            client: String?,
            status: String = "FREE",
            ext: String? = null,
            locationFrom: Int = 1,
            locationTo: Int = 1,
        ) {
            val to = from.plusDays(nights.toLong())
            val price = client ?: "NULL"
            line(
                "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                    "broker_commission, deposit, status, ext_status) VALUES (${++offerId}, $yacht, $locationFrom, $locationTo, " +
                    "DATE '$from', DATE '$to', $price, $price, ${client?.let { "$it * 0.1" } ?: "NULL"}, 1000, '$status', " +
                    "${ext?.let { "'$it'" } ?: "NULL"});",
            )
        }

        fun reservation(
            yacht: Long,
            from: LocalDate,
            to: LocalDate,
            status: String,
            optionExpiration: String? = null,
        ) = line(
            "INSERT INTO external_reservations (id, yacht_id, date_from, date_to, status, option_expiration) VALUES " +
                "(${++reservationId}, $yacht, DATE '$from', DATE '$to', '$status', ${optionExpiration?.let { "TIMESTAMP '$it'" } ?: "NULL"});",
        )
    }

    val SEED: String = seed()

    @Suppress("LongMethod") // one readable table of fixture rows, kept in a single place
    private fun seed(): String =
        with(Seed()) {
            line(
                """
                INSERT INTO country VALUES (54, 'Croatia', 'HR', 'HRV', 'Europe'), (86, 'Greece', 'GR', 'GRC', 'Europe');
                INSERT INTO location (id, name, country_code, lat, lon, country_id, city) VALUES
                    (1, 'Marina Alpha', 'HR', 43.55, 16.36, 54, 'Split'),
                    (2, 'Marina Beta', 'HR', 42.67, 18.12, 54, 'Dubrovnik');
                UPDATE location SET display_name = name || COALESCE(' | ' || city, '');
                INSERT INTO region (id, name, country_id, country_code) VALUES (5, 'Dalmatia', 54, 'HR');
                INSERT INTO location_region VALUES (5, 1), (5, 2);
                INSERT INTO agency (id, name) VALUES (1, 'Agency');
                """.trimIndent(),
            )
            // ---- S: Sat 5.7.2031, 7 nights ----
            // 101: exact Sat-Sat beats a Thu-Thu row; 102: Thu-Thu (-2) only -> SHIFTED; 103: 7 nights at -4 -> outside +-3
            yacht(101)
            offer(101, S, 7, "2100")
            offer(101, S.minusDays(2), 7, "1500")
            yacht(102)
            offer(102, S.minusDays(2), 7, "1600")
            yacht(103)
            offer(103, S.minusDays(4), 7, "1700")
            // 104 (EMA 12210 shape): 14/21/28-night OPTION / FREE blocks only; 105: 3 and 10 nights only
            yacht(104)
            offer(104, S, 14, "3000", "OPTION", "2")
            offer(104, S.minusDays(7), 21, "4000")
            offer(104, S.minusDays(14), 28, "5000", "OPTION", "2")
            yacht(105)
            offer(105, S.plusDays(1), 3, "900")
            offer(105, S, 10, "2500")
            // 106: the searched week is the owner's (MMK ext 6) - a FREE week 2 days later does not bring it back
            yacht(106)
            offer(106, S, 7, "2000", "UNAVAILABLE", "6")
            offer(106, S.plusDays(2), 7, "2200")
            // 107: exact UNAVAILABLE hidden by the MMK two-strike record (not occupancy) - the FREE Thu row 2 days
            // earlier keeps the boat listed as SHIFTED
            yacht(107)
            offer(107, S, 7, "2000", "UNAVAILABLE", "0")
            offer(107, S.minusDays(2), 7, "2300")
            // 108: a RESERVED row overlapping the request (Thu-Thu), a FREE row 2 days later
            yacht(108)
            offer(108, S.minusDays(2), 7, "1800", "RESERVED", "1")
            offer(108, S.plusDays(2), 7, "1900")
            // 109: exact NauSys UNAVAILABLE row with a partner_period_check hide record (not occupancy) - the FREE
            // row 1 day later keeps the boat listed as SHIFTED
            yacht(109)
            offer(109, S, 7, "2000", "UNAVAILABLE", "RESERVED")
            offer(109, S.plusDays(1), 7, "2000")
            // 111: a stale UNAVAILABLE route duplicate (ext 6) next to a FREE row of the same dates
            yacht(111)
            offer(111, S, 7, "2400", "UNAVAILABLE", "6")
            offer(111, S, 7, "2400", locationTo = 2)
            // 112: a Wed-Sat 3-night UNAVAILABLE row inside the FREE Sat-Sat week
            yacht(112)
            offer(112, S.plusDays(4), 3, "1000", "UNAVAILABLE", "6")
            offer(112, S, 7, "2500")
            // 113: a +3 row whose own window runs into a partner reservation that does not touch the request (hull)
            yacht(113)
            offer(113, S.plusDays(3), 7, "2600")
            reservation(113, S.plusDays(8), S.plusDays(12), "RESERVATION")
            // 114: the same with a zombie (expired option mis-statused as RESERVATION): it never blocks
            yacht(114)
            offer(114, S.plusDays(3), 7, "2700")
            reservation(114, S.plusDays(8), S.plusDays(12), "RESERVATION", "2026-01-01 00:00:00")
            // 130 (Angelmiles): 11 EUR weeks and a NULL price only; 131: exactly 300.00 EUR a week
            yacht(PLACEHOLDER)
            offer(PLACEHOLDER, S, 7, "11")
            offer(PLACEHOLDER, S.plusDays(7), 7, "11")
            offer(PLACEHOLDER, S.plusDays(14), 7, null)
            yacht(131)
            offer(131, S, 7, "300.00")
            // 132: custom yacht without a low price
            yacht(CUSTOM, entry = "CUSTOM")
            line("INSERT INTO custom_yacht_details (yacht_id, low_price) VALUES ($CUSTOM, NULL);")
            // 150: a stale OPTION on the exact week, no live hold (A2)
            yacht(OPTION_BOAT)
            offer(OPTION_BOAT, S, 7, "2800", "OPTION", "2")

            // ---- W: Wed 6.8.2031, 7 nights ----
            // 110: cancelled-reservation shape - NauSys Wed-Wed UNAVAILABLE without evidence, FREE Sat row at +3
            yacht(110)
            offer(110, W, 7, "1900", "UNAVAILABLE", "RESERVED")
            offer(110, W.plusDays(3), 7, "2000")
            // 115: a Saturday week 4 days before (Sat 2.8 for Wed 6.8) never qualifies; 116: the exact Wed-Wed week
            yacht(115)
            offer(115, W.minusDays(4), 7, "2100")
            yacht(116)
            offer(116, W, 7, "1900")

            // ---- T: Sat 6.9.2031, 14 nights ----
            // 120 GOLDFINCH: two priced weeks, and the stale 14-night OPTION starting a week early
            yacht(120)
            offer(120, T, 7, "7000")
            offer(120, T.plusDays(7), 7, "7778.89")
            offer(120, T.minusDays(7), 14, "9500", "OPTION", "UNDER_OPTION")
            // 121: a gulet with BAREBOAT + CREWED rows per offer
            yacht(121, vessel = "GULET", types = listOf("BAREBOAT", "CREWED"))
            offer(121, T, 7, "5000")
            offer(121, T.plusDays(7), 7, "6000")
            // 122: two routes in week 2 (2,000 and 2,400)
            yacht(122)
            offer(122, T, 7, "2900")
            offer(122, T.plusDays(7), 7, "2000")
            offer(122, T.plusDays(7), 7, "2400", locationTo = 2)
            // 123: week 2 missing
            yacht(123)
            offer(123, T, 7, "3500")
            // 124: an exact 14-night row beats a cheaper tiling
            yacht(124)
            offer(124, T, 14, "9000")
            offer(124, T, 7, "4000")
            offer(124, T.plusDays(7), 7, "4000")
            // 125: week 2 only from another base (outside did=l-1)
            yacht(125)
            offer(125, T, 7, "2800")
            offer(125, T.plusDays(7), 7, "2900", locationFrom = 2, locationTo = 2)
            // 127: week 2 above a 3,000 EUR/week price ceiling
            yacht(127)
            offer(127, T, 7, "2000")
            offer(127, T.plusDays(7), 7, "4000")

            // ---- Q: Sat 4.10.2031, 3 nights ----
            yacht(140)
            offer(140, Q.plusDays(3), 3, "900")
            yacht(141)
            offer(141, Q.plusDays(2), 3, "950")
            sql.toString()
        }

    /** Evidence rows (V9_59 / V9_82 tables, created after the seed). */
    val EVIDENCE: String =
        """
        INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on, hidden_on, hidden_rows)
            VALUES (107, DATE '$S', DATE '${S.plusDays(7)}', DATE '2026-10-01', DATE '2026-10-02', 1);
        INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, priority, hidden_on, hidden_rows)
            VALUES (2, 109, DATE '$S', DATE '${S.plusDays(7)}', 4, DATE '2026-10-02', 1);
        """.trimIndent()

    /** Schema, seed, the twin matview, capacity columns, the evidence tables and the real search matview. */
    fun create(jdbc: JdbcTemplate) {
        jdbc.execute(SCHEMA)
        val samePlace = ClassPathResource("db/migration/V9_67__location_same_place.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $samePlace; COMMIT;")
        jdbc.execute(SEED)
        ListingTwinTestSupport.createAndRefresh(jdbc)
        YachtCapacityTestSupport.addColumns(jdbc)
        listOf("V9_59__mmk_free_week_strike.sql", "V9_82__real_price_evidence.sql").forEach { file ->
            val migration = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $migration; COMMIT;")
        }
        jdbc.execute(EVIDENCE)
        val view = ClassPathResource("db/migration/R__1_03_yacht_search_view.sql").inputStream.bufferedReader().readText()
        jdbc.execute(
            ConnectionCallback<Unit> { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { it.execute(view) }
                    conn.commit()
                } finally {
                    conn.autoCommit = true
                }
            },
        )
    }

    /** One search of the catalogue. [maxPriceWeekly] is the slider value (EUR a week), as the controllers receive it. */
    data class Request(
        val name: String,
        val start: LocalDate? = null,
        val end: LocalDate? = null,
        val did: List<String>? = null,
        val charterTypes: List<CharterType>? = null,
        val maxPriceWeekly: Int? = null,
        val sortBy: String = "",
        val weekly: Boolean = false,
        val idFrom: Long? = null,
        val idTo: Long? = null,
    ) {
        /** The sidebar facets and the relax hint take this request (no sitemap / weekly-only parameters). */
        val facetable: Boolean get() = idFrom == null && idTo == null
    }

    val REQUESTS: List<Request> =
        listOf(
            Request("S 7n", S, S.plusDays(7)),
            Request("S 7n asc", S, S.plusDays(7), sortBy = "asc"),
            Request("S 7n desc", S, S.plusDays(7), sortBy = "desc"),
            Request("S 7n did l-1", S, S.plusDays(7), did = listOf("l-1")),
            Request("S 7n region", S, S.plusDays(7), did = listOf("r-5")),
            Request("W 7n Wed", W, W.plusDays(7)),
            Request("T 14n asc", T, T.plusDays(14), sortBy = "asc"),
            Request("T 14n desc", T, T.plusDays(14), sortBy = "desc"),
            Request("T 14n did l-1", T, T.plusDays(14), did = listOf("l-1"), sortBy = "asc"),
            Request("T 14n bareboat", T, T.plusDays(14), charterTypes = listOf(CharterType.BAREBOAT), sortBy = "asc"),
            Request("T 14n crewed", T, T.plusDays(14), charterTypes = listOf(CharterType.CREWED), sortBy = "asc"),
            Request("T 14n max 3000", T, T.plusDays(14), maxPriceWeekly = 3000, sortBy = "asc"),
            Request("Q 3n", Q, Q.plusDays(3)),
            Request("undated"),
            Request("undated asc", sortBy = "asc"),
            Request("undated weekly", weekly = true, sortBy = "asc"),
            Request("sitemap shard", idFrom = 100, idTo = 200, sortBy = "id"),
        )

    fun params(
        r: Request,
        size: Int = 100,
    ): YachtSearchParamObject =
        YachtSearchParamObject(
            locationIds = r.did,
            charterTypes = r.charterTypes,
            vesselTypes = null,
            manufacturers = null,
            models = null,
            mainSailTypes = null,
            minBuildYear = null,
            maxBuildYear = null,
            minPersons = null,
            maxPersons = null,
            minCabins = null,
            maxCabins = null,
            minBerths = null,
            maxBerths = null,
            minLength = null,
            maxLength = null,
            minPrice = null,
            // YachtController: the slider's weekly value / 7, two decimals
            maxPrice = r.maxPriceWeekly?.let { BigDecimal(it).divide(BigDecimal(7), 2, RoundingMode.HALF_UP) },
            startDate = r.start,
            endDate = r.end,
            minWc = null,
            maxWc = null,
            minEnginePower = null,
            maxEnginePower = null,
            currency = CurrencyEnum.EUR,
            amenities = null,
            services = null,
            yachtIds = null,
            weeklyPrice = r.weekly,
            idFrom = r.idFrom,
            idTo = r.idTo,
            language = LanguageEnum.EN,
        ).also { check(size > 0) }

    fun distribution(
        service: YachtDistributionService,
        r: Request,
    ) = service.getDistribution(
        locationIds = r.did,
        startDate = r.start,
        endDate = r.end,
        charterTypes = r.charterTypes,
        maxPriceWeekly = r.maxPriceWeekly?.let { BigDecimal(it) },
    )

    fun relaxFilters(r: Request) =
        YachtRelaxSuggestionService.ActiveFilters(
            locationIds = r.did,
            startDate = r.start,
            endDate = r.end,
            charterTypes = r.charterTypes,
            maxPrice = r.maxPriceWeekly,
        )

    /**
     * One request's answer as text: the count, then per card the row the listing mapped (YachtSearchSelectResult with
     * every price, day count, date, status and choice key) with isOption and the match kind, then the facets and the
     * relax baseline. Equal text = equal answers.
     */
    fun canonical(
        r: Request,
        count: Long,
        cards: List<List<Any?>>,
        facets: Any?,
        relaxBaseline: Long?,
    ): String =
        buildString {
            appendLine("## ${r.name}")
            appendLine("count=$count")
            cards.forEach { args -> appendLine("card ${args[0]} isOption=${args[3]} match=${args[6]}") }
            facets?.let { appendLine("facets $it") }
            relaxBaseline?.let { appendLine("relax=$it") }
        }
}
