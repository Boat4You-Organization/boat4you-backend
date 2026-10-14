package hr.workspace.boat4you.domains.catalouge.enums

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * A real [RealPriceSwitches] over an in-memory `settings` table: every switch at its default except the D6 switch
 * `EXTRAS_ZERO_OPTIONAL_ON_SITE`, which is set to [optionalOnSite].
 */
object ExtrasZeroPriceSwitchFixture {
    fun switches(optionalOnSite: Boolean = false): RealPriceSwitches {
        val rows =
            RealPriceSwitches.SPECS.map { spec ->
                val value =
                    if (spec.key == SettingsKeyEnum.EXTRAS_ZERO_OPTIONAL_ON_SITE) {
                        if (optionalOnSite) "ON" else "OFF"
                    } else {
                        spec.defaultText
                    }
                mutableMapOf<String, Any?>("name" to spec.key.name, "value" to value)
            }
        val settings =
            object : JdbcTemplate() {
                override fun queryForList(sql: String): MutableList<MutableMap<String, Any?>> = rows.toMutableList()
            }
        return RealPriceSwitches(settings, ConcurrentMapCacheManager(), Duration.ofSeconds(60))
    }
}
