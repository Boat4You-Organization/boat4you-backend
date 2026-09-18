package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.ImageUtils
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageResizeBusyException
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.opencv.imgproc.Imgproc
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service
class YachtImageService(
    private val yachtImageRepository: YachtImageRepository,
    @Value("\${application.upload.directory}")
    private val uploadDir: String,
    @Value("\${application.images.max-concurrent-resizes}")
    maxConcurrentResizes: Int,
    @Value("\${application.images.resize-wait-ms}")
    private val resizeWaitMs: Long,
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val permits = maxConcurrentResizes.coerceAtLeast(1)

    /**
     * 16.9.2026 cusma2 load incident: `ImageUtils.resizeImage` `imread`s the FULL original, which
     * is tens of MB of OpenCV memory allocated OUTSIDE the heap and outside NMT — at each of the
     * 9 OOM-kills dozens of different image requests were in flight (91 at one) and RSS jumped
     * 2.3 GB -> 5.2 GB in a single minute while heap and NMT stayed flat. Nothing bounded that,
     * so this is the bound. FAIR so a crawler burst cannot starve a real visitor: permits are
     * handed out FIFO instead of to whichever thread wakes up first.
     */
    private val resizeSlots = Semaphore(permits, true)

    /**
     * Bound on the QUEUE, not just on the runners. Tomcat serves this endpoint with ~200 request
     * threads while the gate drains at permits / decode-time (4 permits x ~150 ms ≈ 27 img/s), so
     * every arrival above that rate parks a request thread inside the semaphore's UNBOUNDED FIFO
     * queue. Waiting alone would therefore trade the OOM-kill for a whole-API stall: at ~200
     * parked threads there is no worker left for /public/yachts, /public/reservation or admin, and
     * cusma2 is the only API node. Past this many waiters the request is shed immediately instead.
     */
    private val maxWaiters = permits * MAX_WAITERS_PER_PERMIT
    private val waiting = AtomicInteger()
    private val rejected = AtomicLong()
    private val lastWarnAtMs = AtomicLong()

    /**
     * Test seam, same pattern as `NausysSyncJob.sleep` / `NausysSyncJob.today`: lets a unit test
     * drive the gate with a fake resizer instead of loading OpenCV and a real image.
     */
    internal var resize: (String, Int, Int?) -> ByteArray = { path, width, height ->
        ImageUtils.resizeImage(path, width, height, 90, Imgproc.INTER_LINEAR)
    }

    fun resizeImage(
        imageId: Long,
        width: Int?,
        height: Int?,
    ): Resource {
        // Lookup and the existence check stay OUTSIDE the gate — they cost a Hikari connection
        // and a stat(), never native memory, and holding a permit across them would shrink the
        // effective resize concurrency for no gain.
        val yachtImage =
            yachtImageRepository
                .findById(imageId)
                .orElseThrow { ImageNotFoundException() }

        val file = File(uploadDir + "/" + yachtImage.url)
        if (!file.exists()) {
            throw ImageNotFoundException()
        }

        // No width = serve the stored bytes as-is. There is no OpenCV decode on this path, so it
        // is deliberately not gated (gating it would queue cheap requests behind expensive ones).
        if (width == null) {
            return ByteArrayResource(file.readBytes())
        }

        if (!tryAcquireSlot()) {
            logRejection()
            throw ImageResizeBusyException()
        }
        return try {
            ByteArrayResource(resize(file.absolutePath, width, height))
        } finally {
            // finally, not the happy path only: a failed decode must not leak the permit, or the
            // gate would close permanently after `permits` bad images.
            resizeSlots.release()
        }
    }

    /**
     * Take a slot, or refuse fast. Two ways to be refused: the queue is already deeper than
     * [maxWaiters] (shed without waiting at all), or no permit came free within `resizeWaitMs`.
     * The counter is incremented BEFORE the wait and decremented in `finally` so it measures the
     * threads actually parked here, whatever way they leave.
     */
    private fun tryAcquireSlot(): Boolean {
        if (waiting.incrementAndGet() > maxWaiters) {
            waiting.decrementAndGet()
            return false
        }
        return try {
            resizeSlots.tryAcquire(resizeWaitMs, TimeUnit.MILLISECONDS)
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** How many threads are parked on the gate right now — test seam for the queue bound. */
    internal val queuedForResize: Int
        get() = waiting.get()

    /**
     * Saturation arrives in bursts (that is the whole failure mode), so count every rejection but
     * WARN at most once a minute with the running total — one line per shed request would be the
     * same log flood the incident already produced. Unlike `AsyncConfig`'s rejected-execution
     * handler, which logs every single rejection, this one is deliberately throttled.
     */
    private fun logRejection() {
        val total = rejected.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastWarnAtMs.get()
        if (now - last >= REJECT_WARN_INTERVAL_MS && lastWarnAtMs.compareAndSet(last, now)) {
            log.warn(
                "Image resize gate saturated: {} permits, at most {} waiters, {} ms wait; shedding with 503 — " +
                    "{} rejected since start; next warning in >= 1 min",
                permits,
                maxWaiters,
                resizeWaitMs,
                total,
            )
        }
    }

    companion object {
        private const val REJECT_WARN_INTERVAL_MS = 60_000L

        /**
         * Queue depth allowed per permit. 8 is deep enough to absorb a short burst without
         * shedding a real visitor, and shallow enough that the parked threads stay a small
         * fraction of Tomcat's pool (4 permits -> 32 waiters out of `server.tomcat.threads.max`).
         */
        internal const val MAX_WAITERS_PER_PERMIT = 8
    }
}
