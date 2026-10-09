package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.jdbc.core.JdbcTemplate

/**
 * What an ADMIN listing's per-page offer-charter lookup reads (Offers workspace pill, 9.10.2026) and minimal test
 * schemas leave out: the offer's product and the obligatory offer / yacht charges.
 */
object OfferCharterTestSupport {
    fun addTables(jdbc: JdbcTemplate) {
        jdbc.execute("ALTER TABLE offer ADD COLUMN IF NOT EXISTS product varchar(255)")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS offer_extras (id bigserial PRIMARY KEY, offer_id bigint NOT NULL, name text, " +
                "obligatory boolean NOT NULL DEFAULT false)",
        )
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS yacht_extras (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, name text, " +
                "obligatory boolean NOT NULL DEFAULT false, valid_from date, valid_to date)",
        )
    }
}
