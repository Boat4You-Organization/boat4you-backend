package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.catalouge.services.YachtSearchViewRefresher
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test

/**
 * Unit test of the change-aware skip in [SearchViewRefreshJob] (mocked JdbcTemplate + refresher;
 * the real REFRESH is covered by YachtSearchViewRefresherTest against Testcontainers).
 */
class SearchViewRefreshJobTest {
    private val jdbcTemplate: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val refresher: YachtSearchViewRefresher = mock(YachtSearchViewRefresher::class.java)
    private val job = SearchViewRefreshJob(jdbcTemplate, refresher)

    private fun signatureReturns(vararg values: Long?) {
        `when`(jdbcTemplate.queryForObject(SearchViewRefreshJob.SIGNATURE_SQL, Long::class.java))
            .thenReturn(values.first(), *values.drop(1).toTypedArray())
    }

    @Test
    fun `first tick always refreshes and records the signature`() {
        signatureReturns(100L)
        job.refresh()
        verify(refresher, times(1)).refresh()
    }

    @Test
    fun `unchanged signature skips the refresh`() {
        signatureReturns(100L, 100L)
        job.refresh()
        job.refresh()
        verify(refresher, times(1)).refresh()
    }

    @Test
    fun `changed signature refreshes again`() {
        signatureReturns(100L, 100L, 107L)
        job.refresh()
        job.refresh() // skipped
        job.refresh() // 107 != 100 -> refresh
        verify(refresher, times(2)).refresh()
    }

    @Test
    fun `signature query failure is fail-open`() {
        `when`(jdbcTemplate.queryForObject(SearchViewRefreshJob.SIGNATURE_SQL, Long::class.java))
            .thenThrow(RuntimeException("pg_stat unavailable"))
        job.refresh()
        job.refresh()
        verify(refresher, times(2)).refresh()
    }

    @Test
    fun `null signature is fail-open`() {
        signatureReturns(null, null)
        job.refresh()
        job.refresh()
        verify(refresher, times(2)).refresh()
    }

    @Test
    fun `failed refresh leaves the signature unrecorded so the next tick retries`() {
        signatureReturns(100L, 100L)
        `when`(refresher.refresh()).thenThrow(RuntimeException("lock_timeout")).thenReturn(10L)
        job.refresh() // throws inside -> logged, lastSignature stays null
        job.refresh() // same signature but nothing recorded -> retries
        verify(refresher, times(2)).refresh()
    }
}
