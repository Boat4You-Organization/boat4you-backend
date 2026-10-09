package hr.workspace.boat4you.domains.catalouge.services

import org.springframework.jdbc.core.JdbcTemplate

/**
 * What an ADMIN listing's per-page offer-charter lookup reads (Offers workspace pill, 9.10.2026) and minimal test
 * schemas leave out: the offer's product, the obligatory offer / yacht charges, the bases a yacht charge is limited to
 * (valid_for_bases vs external_bases) and the option holds.
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
                "obligatory boolean NOT NULL DEFAULT false, valid_from date, valid_to date, valid_for_bases bigint[])",
        )
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS external_bases (id bigserial PRIMARY KEY, external_id bigint, agency_id bigint, " +
                "location_id bigint)",
        )
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS external_reservations (id bigint PRIMARY KEY, yacht_id bigint, date_from date NOT NULL, " +
                "date_to date NOT NULL, status text NOT NULL, option_expiration timestamp)",
        )
    }
}
