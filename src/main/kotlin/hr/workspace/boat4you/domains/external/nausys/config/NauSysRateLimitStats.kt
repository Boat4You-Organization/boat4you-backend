package hr.workspace.boat4you.domains.external.nausys.config

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for NauSys throttling, fed by the RestClient
 * interceptors and read by the sync jobs for their run summaries
 * ("429s this run = N"). Monotonic; callers diff snapshots.
 */
@Component
class NauSysRateLimitStats {
    /** Every HTTP 429 response seen by the retry interceptor (including retried ones). */
    val tooManyRequests = AtomicLong()

    /** Calls that gave up: 429 budget exhausted or local gate wait timed out. */
    val exhausted = AtomicLong()

    data class Snapshot(val tooManyRequests: Long, val exhausted: Long) {
        fun since(previous: Snapshot): Snapshot =
            Snapshot(tooManyRequests - previous.tooManyRequests, exhausted - previous.exhausted)
    }

    fun snapshot(): Snapshot = Snapshot(tooManyRequests.get(), exhausted.get())
}
