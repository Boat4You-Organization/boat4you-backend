package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The `cc.*` settings of the AI chat (hand-off wait, takeover return, the breaker thresholds and cooldown): typed, with the
 * documented defaults, and validated on the way in. Rows of the existing `settings` table, read through P1a's [CcSettings]
 * (one cache of the `cc.*` rows, re-read at most every 10 s). A missing row or a value that does not parse or is out of range
 * means the default.
 */
interface CcAiSettings {
    data class Values(
        val handoffMinutes: Int = 5,
        val takeoverReturnMinutes: Int? = null,
        val globalMessagesPerMin: Int = 50,
        val globalSessionsPerMin: Int = 20,
        val globalLocksPerMin: Int = 5,
        val siteMessagesPerMin: Int = 30,
        val siteSessionsPerMin: Int = 10,
        val siteLocksPerMin: Int = 3,
        val breakerCooldownMinutes: Int = 5,
    )

    data class Spec(
        val range: IntRange,
        val nullable: Boolean = false,
    )

    fun current(): Values

    /** Validated upsert; false (nothing written) for an unknown key or a value outside its spec. */
    fun update(
        key: String,
        value: String?,
    ): Boolean

    companion object {
        const val HANDOFF_MINUTES = CcSettings.HANDOFF_MINUTES
        const val TAKEOVER_RETURN_MINUTES = "cc.takeover.return.minutes"
        const val GLOBAL_MESSAGES_PER_MIN = "cc.breaker.global.messages.per.min"
        const val GLOBAL_SESSIONS_PER_MIN = "cc.breaker.global.sessions.per.min"
        const val GLOBAL_LOCKS_PER_MIN = "cc.breaker.global.locks.per.min"
        const val SITE_MESSAGES_PER_MIN = "cc.breaker.site.messages.per.min"
        const val SITE_SESSIONS_PER_MIN = "cc.breaker.site.sessions.per.min"
        const val SITE_LOCKS_PER_MIN = "cc.breaker.site.locks.per.min"
        const val BREAKER_COOLDOWN_MINUTES = "cc.breaker.cooldown.minutes"

        private val THRESHOLD = Spec(1..10000)

        /** Every key an admin may write, with its allowed range; `cc.handoff.minutes` has the range of P1a's [CcSettings.handoffMinutes]. */
        val SPECS: Map<String, Spec> =
            linkedMapOf(
                HANDOFF_MINUTES to Spec(1..120),
                TAKEOVER_RETURN_MINUTES to Spec(1..1440, nullable = true),
                GLOBAL_MESSAGES_PER_MIN to THRESHOLD,
                GLOBAL_SESSIONS_PER_MIN to THRESHOLD,
                GLOBAL_LOCKS_PER_MIN to THRESHOLD,
                SITE_MESSAGES_PER_MIN to THRESHOLD,
                SITE_SESSIONS_PER_MIN to THRESHOLD,
                SITE_LOCKS_PER_MIN to THRESHOLD,
                BREAKER_COOLDOWN_MINUTES to Spec(1..60),
            )

        /** True when [key] is a known AI setting and [value] fits its spec; a blank value fits only a nullable key (it means off). */
        fun isAllowed(
            key: String,
            value: String?,
        ): Boolean {
            val spec = SPECS[key] ?: return false
            val text = value?.trim()
            if (text.isNullOrEmpty()) return spec.nullable
            return text.toIntOrNull()?.let { it in spec.range } ?: false
        }

        /** The typed values of the raw `cc.*` rows: each is trimmed and kept only inside its range, anything else is the [Values] default. */
        fun parse(rows: Map<String, String?>): Values {
            val defaults = Values()

            fun int(key: String): Int? = rows[key]?.trim()?.toIntOrNull()?.takeIf { it in SPECS.getValue(key).range }
            return Values(
                handoffMinutes = int(HANDOFF_MINUTES) ?: defaults.handoffMinutes,
                takeoverReturnMinutes = int(TAKEOVER_RETURN_MINUTES) ?: defaults.takeoverReturnMinutes,
                globalMessagesPerMin = int(GLOBAL_MESSAGES_PER_MIN) ?: defaults.globalMessagesPerMin,
                globalSessionsPerMin = int(GLOBAL_SESSIONS_PER_MIN) ?: defaults.globalSessionsPerMin,
                globalLocksPerMin = int(GLOBAL_LOCKS_PER_MIN) ?: defaults.globalLocksPerMin,
                siteMessagesPerMin = int(SITE_MESSAGES_PER_MIN) ?: defaults.siteMessagesPerMin,
                siteSessionsPerMin = int(SITE_SESSIONS_PER_MIN) ?: defaults.siteSessionsPerMin,
                siteLocksPerMin = int(SITE_LOCKS_PER_MIN) ?: defaults.siteLocksPerMin,
                breakerCooldownMinutes = int(BREAKER_COOLDOWN_MINUTES) ?: defaults.breakerCooldownMinutes,
            )
        }
    }
}

/**
 * Keeps no cache of its own: reads come from [CcSettings] (its 10 s cache, WARN and keep the last values on a failed read),
 * a write goes to the `settings` row and then makes [CcSettings] re-read at once, so the next read here and the one in P1a's
 * missed-conversation marker both see it.
 */
@Component
class JdbcCcAiSettings(
    private val cc: CcSettings,
    private val jdbc: JdbcTemplate,
) : CcAiSettings {
    override fun current(): CcAiSettings.Values = CcAiSettings.parse(CcAiSettings.SPECS.keys.associateWith { cc.string(it) })

    override fun update(
        key: String,
        value: String?,
    ): Boolean {
        if (!CcAiSettings.isAllowed(key, value)) return false
        jdbc.update(UPSERT, key, value?.trim()?.takeIf { it.isNotEmpty() })
        cc.reload()
        return true
    }

    private companion object {
        const val UPSERT =
            "INSERT INTO settings (name, value, created, entity_status) VALUES (?, ?, NOW(), 'ACTIVE') " +
                "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, modified = NOW()"
    }
}
