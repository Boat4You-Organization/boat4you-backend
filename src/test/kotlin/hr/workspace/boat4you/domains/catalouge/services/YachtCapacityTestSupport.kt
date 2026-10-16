package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The yacht columns the listing's per-page lookup reads (capacity contract v1): the crew columns minimal test schemas
 * leave out, then the real V9_72 (the 14 partner capacity columns), V9_85 (the raw partner name, read by the note
 * sanitizer) and V9_90 (the frozen public slug), like Flyway.
 */
object YachtCapacityTestSupport {
    fun addColumns(jdbc: JdbcTemplate) {
        jdbc.execute(
            "ALTER TABLE yacht ADD COLUMN IF NOT EXISTS crew_cabins smallint, ADD COLUMN IF NOT EXISTS crew_berths smallint, " +
                "ADD COLUMN IF NOT EXISTS crew_wc smallint, ADD COLUMN IF NOT EXISTS crew_number smallint, " +
                "ADD COLUMN IF NOT EXISTS draught numeric",
        )
        listOf("V9_72__yacht_partner_capacity.sql", "V9_85__r02_yacht_partner_name_column.sql", "V9_90__yacht_public_slug.sql").forEach { file ->
            val migration = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $migration; COMMIT;")
        }
    }
}
