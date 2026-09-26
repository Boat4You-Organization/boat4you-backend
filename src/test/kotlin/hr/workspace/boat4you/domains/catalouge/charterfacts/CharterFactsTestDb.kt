package hr.workspace.boat4you.domains.catalouge.charterfacts

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The minimal schema the charter-facts job reads, plus the REAL search matview (R__1_03, the listing count the facts
 * mirror since B12) and the real V9_61 / V9_67 migrations. No Spring context / Flyway: the versioned history cannot
 * be replayed on an empty database (V9_18 is a prod data fix that assumes seeded rows).
 */
object CharterFactsTestDb {
    val MINIMAL_SCHEMA =
        """
        CREATE TABLE country (id int PRIMARY KEY, code2 varchar(2) NOT NULL);
        CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_code varchar(2) NOT NULL,
                               city varchar(100), lat numeric, lon numeric, display_name text);
        CREATE TABLE region (id int PRIMARY KEY, name varchar(100), country_code varchar(2));
        CREATE TABLE location_region (region_id int NOT NULL, location_id bigint NOT NULL);
        CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                             availability_blocked boolean NOT NULL DEFAULT false, recommended boolean);
        CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
        CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
        CREATE TABLE yacht (id bigint PRIMARY KEY, name text, agency_id bigint, entry_type varchar(31) NOT NULL,
                            sys_active boolean NOT NULL DEFAULT true, build_year smallint, model_id bigint,
                            vessel_type varchar(31) NOT NULL, deposit numeric, deposit_currency varchar(20),
                            location_id bigint, mainsail_type text, max_persons smallint, cabins smallint, berths smallint,
                            length numeric, wc smallint, engine_power numeric, main_image_id bigint);
        CREATE TABLE yacht_charter_type (id bigserial PRIMARY KEY, yacht_id bigint, type text);
        CREATE TABLE custom_yacht_details (yacht_id bigint PRIMARY KEY, low_price numeric);
        CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, location_from bigint NOT NULL,
                            location_to bigint NOT NULL, date_from date NOT NULL, date_to date NOT NULL,
                            client_price numeric NOT NULL, status varchar(31) NOT NULL, ext_base_price numeric,
                            broker_commission numeric, deposit numeric);
        CREATE TABLE yacht_extras (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, name varchar, price numeric NOT NULL,
                                   unit varchar(31) NOT NULL, obligatory boolean NOT NULL, valid_from date, valid_to date,
                                   extras_id bigint);
        CREATE TABLE external_mapping (id bigserial PRIMARY KEY, external_id bigint, system_id bigint, type varchar(100),
                                       external_system_id int, extended_type varchar(100));
        """.trimIndent()

    /** Schema + the real migrations the job depends on, each in a transaction like Flyway (they use SET LOCAL). */
    fun create(jdbc: JdbcTemplate) {
        jdbc.execute(MINIMAL_SCHEMA)
        listOf("V9_61__charter_facts.sql", "V9_67__location_same_place.sql", "V9_69__yacht_listing_twin.sql").forEach { file ->
            val migration = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $migration; COMMIT;")
        }
    }

    /** (Re)build the search matview from the seeded rows, exactly as Flyway runs R__1_03, and the twin matview. */
    fun refreshSearchView(jdbc: JdbcTemplate) {
        jdbc.execute("UPDATE location SET display_name = name || COALESCE(' | ' || NULLIF(btrim(city), ''), '')")
        jdbc.execute("REFRESH MATERIALIZED VIEW yacht_listing_twin")
        val sql = ClassPathResource("db/migration/R__1_03_yacht_search_view.sql").inputStream.bufferedReader().readText()
        jdbc.execute(
            ConnectionCallback<Unit> { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { it.execute(sql) }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            },
        )
    }
}
