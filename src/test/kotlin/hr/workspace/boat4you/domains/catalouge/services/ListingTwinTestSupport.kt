package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The real V9_69 + V9_70 yacht_listing_twin matview on a test's minimal schema (the undated search, the facets and the
 * charter facts read it since audit B17): adds the relations and columns it reads that minimal schemas often lack,
 * creates the matview and fills it from the rows seeded so far.
 */
object ListingTwinTestSupport {
    fun createAndRefresh(jdbc: JdbcTemplate) {
        jdbc.execute("ALTER TABLE location ADD COLUMN IF NOT EXISTS name varchar(255)")
        // the copy-shown rule reads the channel: inquiry-only agency (V9_36) / option approval (Yacht.isInquireOnly)
        jdbc.execute("ALTER TABLE agency ADD COLUMN IF NOT EXISTS inquiry_only boolean NOT NULL DEFAULT false")
        jdbc.execute("ALTER TABLE yacht ADD COLUMN IF NOT EXISTS option_approval boolean")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS external_mapping (id bigserial PRIMARY KEY, external_id bigint, system_id bigint, " +
                "type varchar(100), external_system_id int, extended_type varchar(100))",
        )
        // V9_70 rebuilds the matview with the hand-verified pairs (yacht_twin_manual_pair): run both, like Flyway
        listOf("V9_69__yacht_listing_twin.sql", "V9_70__yacht_twin_manual_pair.sql").forEach { file ->
            val migration = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $migration; COMMIT;")
        }
        refresh(jdbc)
    }

    fun refresh(jdbc: JdbcTemplate) = jdbc.execute("REFRESH MATERIALIZED VIEW public.yacht_listing_twin")
}
