package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The real V9_69 yacht_listing_twin matview on a test's minimal schema (the undated search, the facets and the
 * charter facts read it since audit B17): adds the two relations it reads that minimal schemas often lack, creates
 * the matview and fills it from the rows seeded so far.
 */
object ListingTwinTestSupport {
    fun createAndRefresh(jdbc: JdbcTemplate) {
        jdbc.execute("ALTER TABLE location ADD COLUMN IF NOT EXISTS name varchar(255)")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS external_mapping (id bigserial PRIMARY KEY, external_id bigint, system_id bigint, " +
                "type varchar(100), external_system_id int, extended_type varchar(100))",
        )
        val migration = ClassPathResource("db/migration/V9_69__yacht_listing_twin.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $migration; COMMIT;")
        refresh(jdbc)
    }

    fun refresh(jdbc: JdbcTemplate) = jdbc.execute("REFRESH MATERIALIZED VIEW public.yacht_listing_twin")
}
