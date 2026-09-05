package hr.workspace.boat4you.domains.external.mmk.config

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide MMK throttling counter, fed by the RestClient interceptor and read by the
 * sync jobs for their run summaries ("429s this run = N"). Mirrors [hr.workspace.boat4you.domains.external.nausys.config.NauSysRateLimitStats];
 * monotonic, callers diff snapshots.
 */
@Component
class MmkRequestStats {
    /** Every HTTP 429 response seen by the interceptor (including ones @Retryable retries). */
    val tooManyRequests = AtomicLong()

    data class Snapshot(
        val tooManyRequests: Long,
    ) {
        fun since(previous: Snapshot): Snapshot = Snapshot(tooManyRequests - previous.tooManyRequests)
    }

    fun snapshot(): Snapshot = Snapshot(tooManyRequests.get())
}
