package hr.workspace.boat4you.domains.catalouge.charterfacts

import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
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
        verify(compute, timeout(5_000)).recompute()
        unlocked.await(5, TimeUnit.SECONDS) shouldBe true
    }
}
