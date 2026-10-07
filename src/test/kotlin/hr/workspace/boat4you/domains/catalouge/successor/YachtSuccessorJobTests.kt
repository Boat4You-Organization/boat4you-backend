package hr.workspace.boat4you.domains.catalouge.successor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.util.Optional

/** Review 7.10.2026: stored successors that stop being recomputed (the shrink guard keeps refusing) must not go unnoticed. */
class YachtSuccessorJobTests {
    private val compute: YachtSuccessorComputeService = mock(YachtSuccessorComputeService::class.java)
    private val job = YachtSuccessorJob(compute) { Optional.empty() }

    @Test
    fun `staleness - fresh rows are fine, older than 48 h, none at all or an unreadable table is reported`() {
        val now = Instant.parse("2026-10-08T07:55:00Z")
        `when`(compute.latestComputedAt()).thenReturn(now.minus(Duration.ofHours(24)))
        job.checkStaleness(now) shouldBe false
        `when`(compute.latestComputedAt()).thenReturn(now.minus(Duration.ofHours(49)))
        job.checkStaleness(now) shouldBe true
        `when`(compute.latestComputedAt()).thenReturn(null)
        job.checkStaleness(now) shouldBe true
        `when`(compute.latestComputedAt()).thenThrow(IllegalStateException("relation \"yacht_successor\" does not exist"))
        job.checkStaleness(now) shouldBe true
    }

    @Test
    fun `the daily run checks the age of the stored rows, also when the recompute fails`() {
        `when`(compute.recompute()).thenThrow(IllegalStateException("statement timeout"))
        job.recomputeDaily()
        verify(compute).latestComputedAt()
    }
}
