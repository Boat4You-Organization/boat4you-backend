package hr.workspace.boat4you.common.config

import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * A real [RealPriceSwitches] over an in-memory `settings` table: every switch at its default (today's behaviour)
 * except the ones in [overrides]. The values are fixed per instance (the snapshot is cached for 60 s).
 */
object RealPriceSwitchesFixture {
    fun switches(overrides: Map<SettingsKeyEnum, String> = emptyMap()): RealPriceSwitches {
        val rows =
            RealPriceSwitches.SPECS.map { spec ->
                mutableMapOf<String, Any?>("name" to spec.key.name, "value" to (overrides[spec.key] ?: spec.defaultText))
            }
        val settings =
            object : JdbcTemplate() {
                override fun queryForList(sql: String): MutableList<MutableMap<String, Any?>> = rows.toMutableList()
            }
        return RealPriceSwitches(settings, ConcurrentMapCacheManager(), Duration.ofSeconds(60))
    }

    /** Defaults everywhere, `NAUSYS_WARM_LONG_RANGES` set to ON or OFF. */
    fun warmLongRanges(on: Boolean): RealPriceSwitches = switches(mapOf(SettingsKeyEnum.NAUSYS_WARM_LONG_RANGES to if (on) "ON" else "OFF"))
}
