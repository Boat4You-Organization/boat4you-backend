package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** Recording switch of one site (P6 R1): on or off, the share of visits with analytics consent recorded, and of mobile visits. */
data class RecSiteSetting(
    val siteId: String,
    val enabled: Boolean,
    val samplePct: Int,
    val mobilePct: Int,
)

/**
 * The `rec_setting` rows, read at most once per [ttl] by the public endpoints (the widget asks every 5 minutes per visitor).
 * A site without a row is off and records everything once switched on. A failed read keeps the last rows (none: all off).
 */
@Component
class RecSettingsStore(
    private val jdbc: JdbcTemplate,
    @Value("\${cc.rec.settings-ttl:PT10S}") private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Monotonic clock in nanoseconds; replaced in tests. */
    internal var ticker: () -> Long = System::nanoTime

    @Volatile private var cached: Pair<Map<String, RecSiteSetting>, Long>? = null

    fun forSite(siteId: String): RecSiteSetting = rows()[siteId] ?: defaults(siteId)

    fun all(): List<RecSiteSetting> = SiteIds.ALL.map(::forSite)

    fun update(
        setting: RecSiteSetting,
        userId: Long?,
    ) {
        jdbc.update(
            "INSERT INTO rec_setting (site_id, enabled, sample_pct, mobile_pct, updated_at, updated_by) VALUES (?, ?, ?, ?, now(), ?) " +
                "ON CONFLICT (site_id) DO UPDATE SET enabled = EXCLUDED.enabled, sample_pct = EXCLUDED.sample_pct, " +
                "mobile_pct = EXCLUDED.mobile_pct, updated_at = now(), updated_by = EXCLUDED.updated_by",
            setting.siteId,
            setting.enabled,
            setting.samplePct,
            setting.mobilePct,
            userId,
        )
        cached = null
    }

    @Suppress("TooGenericExceptionCaught") // a failed read must never break the public endpoints
    private fun rows(): Map<String, RecSiteSetting> {
        val now = ticker()
        cached?.let { (rows, at) -> if (now - at < ttl.toNanos()) return rows }
        return try {
            jdbc
                .query("SELECT site_id, enabled, sample_pct, mobile_pct FROM rec_setting") { rs, _ ->
                    RecSiteSetting(rs.getString("site_id"), rs.getBoolean("enabled"), rs.getInt("sample_pct"), rs.getInt("mobile_pct"))
                }.associateBy { it.siteId }
                .also { cached = it to now }
        } catch (e: Exception) {
            log.warn("rec settings not read, keeping the last ones: {}", e.toString())
            cached?.first ?: emptyMap()
        }
    }

    companion object {
        fun defaults(siteId: String) = RecSiteSetting(siteId, enabled = false, samplePct = 100, mobilePct = 100)
    }
}
