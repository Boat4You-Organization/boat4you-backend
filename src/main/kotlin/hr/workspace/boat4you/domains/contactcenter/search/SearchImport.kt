package hr.workspace.boat4you.domains.contactcenter.search

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Copies the search engine reports into V9_118. Google: every site, newest day first, the days never imported or imported
 * before Google settled them ([SETTLED_DAYS]) and not in the last [REFRESH_HOURS] hours, at most [daysPerRun] per site, back to
 * [GOOGLE_HISTORY_DAYS] (Google keeps 16 months), so the first runs fill the history and later runs refresh the recent days.
 * Bing: once in [BING_EVERY_HOURS], all it still keeps. A day is replaced per engine, site and grain in one transaction;
 * a failing site is logged and left for the next run.
 */
@Service
@Suppress("TooGenericExceptionCaught") // any failure of one site or engine is logged and left for the next run
class SearchImport(
    private val jdbc: JdbcTemplate,
    private val google: GoogleSearchApi,
    private val bing: BingSearchApi,
    txManager: PlatformTransactionManager,
    @Value("\${seo.backfill-days-per-run:30}") private val daysPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    /** When this node last asked Bing: an answer that stores nothing must not make every hourly run ask again. */
    @Volatile private var bingAskedAt: Instant? = null

    data class Result(
        val googleDays: Int,
        val bingDays: Int,
        val rows: Int,
        val failures: Int,
    )

    fun run(today: LocalDate = LocalDate.now(GOOGLE_ZONE)): Result {
        var result = Result(0, 0, 0, 0)
        if (google.enabled) SiteIds.ALL.forEach { result = result.plus(googleSite(it, today)) }
        if (bing.enabled && bingDue()) result = result.plus(bingSites())
        return result
    }

    /** The days of [siteId] the next Google import takes, newest first. */
    fun googleDays(
        siteId: String,
        today: LocalDate,
    ): List<LocalDate> {
        val newest = today.minusDays(GOOGLE_LAG_DAYS)
        val oldest = today.minusDays(GOOGLE_HISTORY_DAYS)
        val done =
            jdbc
                .queryForList(
                    "SELECT day FROM seo_import_day WHERE engine = 'GOOGLE' AND site_id = ? AND day BETWEEN ? AND ? " +
                        "AND (imported_at >= day + $SETTLED_DAYS OR imported_at > now() - make_interval(hours => $REFRESH_HOURS))",
                    Date::class.java,
                    siteId,
                    Date.valueOf(oldest),
                    Date.valueOf(newest),
                ).map { it.toLocalDate() }
                .toSet()
        return generateSequence(newest) { it.minusDays(1) }
            .takeWhile { !it.isBefore(oldest) }
            .filter { it !in done }
            .take(daysPerRun)
            .toList()
    }

    private fun googleSite(
        siteId: String,
        today: LocalDate,
    ): Result {
        var days = 0
        var rows = 0
        for (day in googleDays(siteId, today)) {
            val report =
                try {
                    SearchGrain.entries.associateWith { google.rows(siteId, day, it) }
                } catch (e: Exception) {
                    log.warn("Google Search Console import of {} {} failed: {}", siteId, day, e.toString())
                    return Result(days, 0, rows, 1)
                }
            rows += store(GOOGLE, siteId, day, report)
            days++
        }
        return Result(days, 0, rows, 0)
    }

    private fun bingDue(): Boolean =
        bingAskedAt?.isAfter(Instant.now().minus(Duration.ofHours(BING_EVERY_HOURS.toLong()))) != true &&
            jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM seo_import_day WHERE engine = 'BING' AND imported_at > now() - make_interval(hours => $BING_EVERY_HOURS))",
                Boolean::class.java,
            ) == true

    private fun bingSites(): Result {
        bingAskedAt = Instant.now()
        val sites =
            try {
                bing.sites()
            } catch (e: Exception) {
                log.warn("Bing Webmaster site list failed: {}", e.message)
                return Result(0, 0, 0, 1)
            }
        if (sites.isEmpty()) log.warn("Bing Webmaster lists none of the seven sites as verified")
        var result = Result(0, 0, 0, 0)
        for ((siteId, url) in sites) {
            val report =
                try {
                    SearchGrain.entries.associateWith { bing.rows(url, it) }
                } catch (e: Exception) {
                    log.warn("Bing Webmaster import of {} failed: {}", siteId, e.message)
                    result = result.plus(Result(0, 0, 0, 1))
                    continue
                }
            // A grain is replaced only on the days Bing still reports for it: older weeks stay as imported.
            val days = report.values.flatMap { it.keys }.toSortedSet()
            days.forEach { day ->
                val rows = store(BING, siteId, day, report.filterValues { day in it }.mapValues { it.value.getValue(day) })
                result = result.plus(Result(0, 1, rows, 0))
            }
        }
        return result
    }

    /** Replaces the given grains of one engine, site and day and stamps the day; returns the rows written. */
    private fun store(
        engine: String,
        siteId: String,
        day: LocalDate,
        report: Map<SearchGrain, List<SearchRow>>,
    ): Int =
        tx.execute {
            val date = Date.valueOf(day)
            report.forEach { (grain, rows) ->
                jdbc.update("DELETE FROM ${grain.table} WHERE engine = ? AND site_id = ? AND day = ?", engine, siteId, date)
                val text = TEXT_COLUMNS.getValue(grain)
                jdbc.batchUpdate(
                    "INSERT INTO ${grain.table} (engine, site_id, day, ${text.joinToString("") { "$it, " }}country, device, clicks, impressions, " +
                        "position_sum) VALUES (${"?, ".repeat(3 + text.size)}?, ?, ?, ?, ?)",
                    rows.map { r ->
                        val values = text.map { if (it == "query") r.query else r.path }
                        (listOf<Any>(engine, siteId, date) + values + listOf(r.country, r.device, r.clicks, r.impressions, r.positionSum.toFloat()))
                            .toTypedArray()
                    },
                )
            }
            jdbc.update(
                "INSERT INTO seo_import_day (engine, site_id, day) VALUES (?, ?, ?) " +
                    "ON CONFLICT (engine, site_id, day) DO UPDATE SET imported_at = now()",
                engine,
                siteId,
                date,
            )
            report.values.sumOf { it.size }
        } ?: 0

    private fun Result.plus(o: Result) = Result(googleDays + o.googleDays, bingDays + o.bingDays, rows + o.rows, failures + o.failures)

    companion object {
        const val GOOGLE = "GOOGLE"
        const val BING = "BING"

        /** Google reports days in Pacific time and has the last two days only partly. */
        val GOOGLE_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        const val GOOGLE_LAG_DAYS = 2L
        const val GOOGLE_HISTORY_DAYS = 480L
        const val SETTLED_DAYS = 4
        const val REFRESH_HOURS = 12
        const val BING_EVERY_HOURS = 20

        /** The text columns of each grain, in insert order. */
        private val TEXT_COLUMNS =
            mapOf(SearchGrain.SITE to emptyList(), SearchGrain.PAGE to listOf("path"), SearchGrain.QUERY to listOf("query", "path"))
    }
}
