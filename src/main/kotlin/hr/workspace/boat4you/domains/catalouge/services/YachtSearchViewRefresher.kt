package hr.workspace.boat4you.domains.catalouge.services

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The single place that runs `REFRESH MATERIALIZED VIEW CONCURRENTLY public.yacht_search_view`
 * (used by the cron in SearchViewRefreshJob on the scheduler node and by the on-demand
 * SearchViewRefreshService on the api node).
 *
 * Why session tuning: CONCURRENTLY diffs the new snapshot against the live matview with a
 * FULL JOIN of all ~1.7M rows on row_uid. Measured on prod (2.9.2026) that hash needs ~741 MB;
 * with the default work_mem 32MB × hash_mem_multiplier 2 = 64 MB it batched to disk —
 * ~1.8 GB of temp files per run, 288 runs/day ≈ 530 GB/day of temp I/O, 40-60 s per refresh —
 * and on cusma2 the on-demand path died outright against the 1 GB `temp_file_limit` in the
 * Hikari connectionInitSql. With work_mem 768MB (× multiplier 2 = 1.5 GB cap, ~2× headroom on
 * row growth) the diff stays in RAM and produces no temp file at all, so the temp_file_limit
 * never bites. lock_timeout is raised so a collision with the other node's refresh waits
 * instead of failing at the role default 15 s. All three GUCs are USERSET (no GRANT needed)
 * and are RESET in `finally` so the pooled Hikari connection goes back clean.
 *
 * Runs in auto-commit on one pooled connection — deliberately NOT @Transactional.
 */
@Component
class YachtSearchViewRefresher(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    /** Refreshes the matview and returns the wall-clock duration in ms. Throws on failure. */
    fun refresh(): Long {
        val start = System.currentTimeMillis()
        jdbcTemplate.execute(
            ConnectionCallback<Unit> { conn ->
                conn.createStatement().use { st ->
                    st.execute("SET work_mem = '768MB'")
                    st.execute("SET lock_timeout = '180s'")
                    st.execute("SET jit = off")
                    try {
                        st.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY public.yacht_search_view")
                    } finally {
                        st.execute("RESET work_mem")
                        st.execute("RESET lock_timeout")
                        st.execute("RESET jit")
                    }
                }
                Unit
            },
        )
        val ms = System.currentTimeMillis() - start
        log.debug("REFRESH MATERIALIZED VIEW CONCURRENTLY yacht_search_view took {} ms", ms)
        return ms
    }
}
