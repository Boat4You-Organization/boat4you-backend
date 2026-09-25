package hr.workspace.boat4you.domains.catalouge.charterfacts

import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CharterFactsJobTests {
    private val compute: CharterFactsComputeService = mock(CharterFactsComputeService::class.java)

    @Test
    fun `manual trigger does not start when the cron's lock is held`() {
        var askedFor: String? = null
        val job =
            CharterFactsJob(compute) { cfg ->
                askedFor = cfg.name
                Optional.empty()
            }
        job.recomputeInBackground() shouldBe false
        askedFor shouldBe CharterFactsJob.LOCK_NAME
        verify(compute, never()).recompute()
    }

    @Test
    fun `manual trigger runs in the background and releases the lock afterwards`() {
        val unlocked = CountDownLatch(1)
        val lockProvider = LockProvider { Optional.of(SimpleLock { unlocked.countDown() }) }
        CharterFactsJob(compute, lockProvider).recomputeInBackground() shouldBe true
        verify(compute, timeout(5_000)).recompute(false)
        unlocked.await(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `manual trigger passes force through`() {
        val lockProvider = LockProvider { Optional.of(SimpleLock { }) }
        CharterFactsJob(compute, lockProvider).recomputeInBackground(force = true) shouldBe true
        verify(compute, timeout(5_000)).recompute(true)
    }

    @Test
    fun `staleness - fresh facts are fine, older than 48 h or none at all is reported`() {
        val now = Instant.parse("2026-09-25T08:00:00Z")
        val job = CharterFactsJob(compute) { Optional.empty() }
        `when`(compute.latestComputedAt()).thenReturn(now.minus(Duration.ofHours(24)))
        job.checkStaleness(now) shouldBe false
        `when`(compute.latestComputedAt()).thenReturn(now.minus(Duration.ofHours(49)))
        job.checkStaleness(now) shouldBe true
        `when`(compute.latestComputedAt()).thenReturn(null)
        job.checkStaleness(now) shouldBe true
    }

    @Test
    fun `cron runs at 08-00 UTC - after the NauSys nightly sync, inside the 07-50 to 08-40 window`() {
        val next = CronExpression.parse(CharterFactsJob.CRON).next(LocalDateTime.of(2026, 9, 25, 0, 0))!!
        next shouldBe LocalDateTime.of(2026, 9, 25, 8, 0)
        val scheduled = CharterFactsJob::class.java.getMethod("recomputeNightly").getAnnotation(Scheduled::class.java)
        scheduled.cron shouldBe CharterFactsJob.CRON
        scheduled.zone shouldBe "UTC"
    }
}
