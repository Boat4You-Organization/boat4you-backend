package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.exceptions.ImageResizeBusyException
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 16.9.2026 cusma2 load incident, finding 1: nothing bounded how many `/public/image/{id}?width=…`
 * requests could be decoding a full original with OpenCV at once (91 concurrent at one OOM-kill),
 * and that native memory is invisible to heap and NMT alike. The gate added to [YachtImageService]
 * is what bounds it, so these tests pin the properties it has to hold: the cap is real, a caller
 * that cannot get a slot in time is shed rather than queued forever, a queue deeper than the
 * waiter bound is shed without parking a Tomcat worker at all, and a failing resize gives its
 * permit back.
 *
 * The `resize` hook is the project's `internal var` test seam (same as `NausysSyncJob.sleep`), so
 * no OpenCV and no real image are involved.
 */
class YachtImageResizeGateTests {
    private val repository: YachtImageRepository = mock(YachtImageRepository::class.java)

    @TempDir
    lateinit var uploadDir: Path

    private fun service(
        permits: Int,
        waitMs: Long,
    ): YachtImageService {
        File(uploadDir.toFile(), IMAGE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        `when`(repository.findById(IMAGE_ID))
            .thenReturn(Optional.of(YachtImage().apply { url = IMAGE_FILE }))
        return YachtImageService(repository, uploadDir.toString(), permits, waitMs)
    }

    @Test
    fun `at most permits resizes run at once`() {
        val permits = 2
        val callers = 8
        val service = service(permits, waitMs = 10_000)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val served = AtomicInteger()
        // Nobody may leave the stub before `permits` callers are inside TOGETHER. That makes the
        // peak a fact rather than a scheduling race: the old spin-with-deadline let the first
        // thread proceed alone on a loaded box (this machine runs load > 8 during parallel work),
        // which read peak=1 and failed a test whose subject — the cap — was not broken.
        val together = CountDownLatch(permits)
        service.resize = { _, _, _ ->
            val now = inFlight.incrementAndGet()
            peak.getAndUpdate { maxOf(it, now) }
            together.countDown()
            together.await(10, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            ByteArray(0)
        }

        val threads =
            (1..callers).map {
                thread {
                    service.resizeImage(IMAGE_ID, 640, null)
                    served.incrementAndGet()
                }
            }
        threads.forEach { it.join(30_000) }

        assertEquals(callers, served.get(), "every caller should eventually be served, not shed")
        // The invariant the gate must hold, and the reason it exists at all.
        assertTrue(peak.get() <= permits, "the gate let ${peak.get()} resizes run at once, cap is $permits")
        // …and it really did run `permits` at once, so the cap above is not passing vacuously.
        assertTrue(peak.get() >= permits, "only ${peak.get()} of $permits slots were ever used at once")
    }

    @Test
    fun `a queue deeper than the waiter bound is shed immediately instead of parking the thread`() {
        // 16.9.2026 cusma2 load incident review: the semaphore's own queue is unbounded, so with
        // ~200 Tomcat workers a sustained burst would park the whole pool behind the gate and
        // leave nothing for /public/yachts or /public/reservation — the OOM traded for an API
        // stall. Past `permits * MAX_WAITERS_PER_PERMIT` waiters the request must be shed at once.
        val permits = 1
        val bound = permits * YachtImageService.MAX_WAITERS_PER_PERMIT
        val service = service(permits, waitMs = 30_000)
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.resize = { _, _, _ ->
            holderInside.countDown()
            release.await(20, TimeUnit.SECONDS)
            ByteArray(0)
        }
        val holder = thread { service.resizeImage(IMAGE_ID, 640, null) }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        // Fill the queue exactly to the bound. These park (30 s wait), they are not shed.
        val queued = (1..bound).map { thread { runCatching { service.resizeImage(IMAGE_ID, 640, null) } } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.queuedForResize < bound && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(bound, service.queuedForResize, "the first $bound callers must queue, not be shed")

        val startedAt = System.nanoTime()
        assertFailsWith<ImageResizeBusyException> { service.resizeImage(IMAGE_ID, 640, null) }
        val shedAfterMs = (System.nanoTime() - startedAt) / 1_000_000
        // Shed, not parked: the request must come back long before the 30 s wait it would have
        // served otherwise.
        assertTrue(shedAfterMs < 1_000, "one-too-many caller was parked for $shedAfterMs ms instead of shed")

        release.countDown()
        holder.join(10_000)
        queued.forEach { it.join(10_000) }
    }

    @Test
    fun `a caller that cannot get a slot within the wait is shed with the busy exception`() {
        val service = service(permits = 1, waitMs = 50)
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.resize = { _, _, _ ->
            holderInside.countDown()
            release.await(5, TimeUnit.SECONDS)
            ByteArray(0)
        }
        val holder = thread { service.resizeImage(IMAGE_ID, 640, null) }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        assertFailsWith<ImageResizeBusyException> { service.resizeImage(IMAGE_ID, 640, null) }

        release.countDown()
        holder.join(5_000)
    }

    @Test
    fun `a failing resize releases its permit`() {
        val service = service(permits = 1, waitMs = 50)
        service.resize = { _, _, _ -> throw IllegalArgumentException("Could not load image") }

        repeat(3) {
            assertFailsWith<IllegalArgumentException> { service.resizeImage(IMAGE_ID, 640, null) }
        }

        // The only permit must still be available; without the `finally` the first failure would
        // have closed the gate for good and this would be an ImageResizeBusyException instead.
        service.resize = { _, _, _ -> byteArrayOf(9) }
        assertEquals(1L, service.resizeImage(IMAGE_ID, 640, null).contentLength())
    }

    @Test
    fun `the unresized passthrough does not take a permit`() {
        val service = service(permits = 1, waitMs = 50)
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.resize = { _, _, _ ->
            holderInside.countDown()
            release.await(5, TimeUnit.SECONDS)
            ByteArray(0)
        }
        val holder = thread { service.resizeImage(IMAGE_ID, 640, null) }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        // No width = no OpenCV decode, so it must be served while the only permit is held.
        assertEquals(3L, service.resizeImage(IMAGE_ID, null, null).contentLength())

        release.countDown()
        holder.join(5_000)
    }

    companion object {
        private const val IMAGE_ID = 1L
        private const val IMAGE_FILE = "yacht.webp"
    }
}
