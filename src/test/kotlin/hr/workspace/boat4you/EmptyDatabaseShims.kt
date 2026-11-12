package hr.workspace.boat4you

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.springframework.core.io.ClassPathResource

/**
 * The three shims the Flyway chain (src/main/resources/db/migration) needs on an EMPTY database, nothing else, run on the
 * migration's own connection. The chain was written against a live database: V9_18 (a prod data fix) re-links location
 * 2029 to region 5, so both rows are stubbed first (FK targets only); V9_50 reads `reservation_view`, which the repeatable
 * R__1_02 creates and Flyway only runs after all versioned files, so R__1_02 is applied once just before V9_50 (Flyway
 * then applies every R__ file itself at the end, as on prod).
 */
object EmptyDatabaseShims : Callback {
    override fun supports(
        event: Event,
        context: Context?,
    ) = event == Event.BEFORE_EACH_MIGRATE

    override fun canHandleInTransaction(
        event: Event,
        context: Context?,
    ) = true

    override fun handle(
        event: Event,
        context: Context,
    ) {
        val sql =
            when (context.migrationInfo?.version?.toString()) {
                "9.18" ->
                    "SET LOCAL session_replication_role = replica; " +
                        "INSERT INTO region (id) VALUES (5); " +
                        "INSERT INTO location (id, name, country_code, country_id) VALUES (2029, 'stub', 'HR', 0); " +
                        "SET LOCAL session_replication_role = origin"
                "9.50" -> ClassPathResource("db/migration/R__1_02_reservation_view.sql").inputStream.readAllBytes().decodeToString()
                else -> return
            }
        context.connection.createStatement().use { it.execute(sql) }
    }

    override fun getCallbackName() = "test-empty-db-shims"
}
