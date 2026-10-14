package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * Real [RealPriceSwitches] over an in-memory `settings` table for the search tests: no rows = every default (today's
 * behaviour); [SearchSwitches.set] flips a switch, seen by the very next read (TTL 0).
 */
object SearchSwitchesTestSupport {
    class SearchSwitches : JdbcTemplate() {
        private val rows = linkedMapOf<SettingsKeyEnum, String>()

        val switches: RealPriceSwitches = RealPriceSwitches(this, NoOpCacheManager(), Duration.ZERO)

        override fun queryForList(sql: String): MutableList<MutableMap<String, Any?>> =
            rows.map { (key, value) -> mutableMapOf<String, Any?>("name" to key.name, "value" to value) }.toMutableList()

        fun set(
            key: SettingsKeyEnum,
            value: String,
        ): SearchSwitches = apply { rows[key] = value }

        fun reset(): SearchSwitches = apply { rows.clear() }

        /** SEARCH_PREDICATE / SEARCH_PRICE_FLOOR / SEARCH_DEMOTE_STALE_OPTIONS in one call. */
        fun search(
            predicate: String = "LEGACY",
            floor: String = "OFF",
            demoteStaleOptions: String = "true",
        ): SearchSwitches =
            set(SettingsKeyEnum.SEARCH_PREDICATE, predicate)
                .set(SettingsKeyEnum.SEARCH_PRICE_FLOOR, floor)
                .set(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, demoteStaleOptions)
    }

    /** All switches at their defaults (= today's search). */
    fun defaults(): RealPriceSwitches = SearchSwitches().switches
}
