package hr.workspace.boat4you.domains.external.exceptions

/**
 * Thrown once the NauSys 429 ("Too many concurrent requests for user ...")
 * retry budget is exhausted for a single HTTP call, or when the local
 * per-JVM concurrency gate could not obtain a slot in time (`attempts = 0`).
 *
 * Subclasses [ExternalSystemException] so the REST layer keeps mapping it to
 * the friendly EXTERNAL_SYSTEM_ERROR response and `@Retryable(noRetryFor =
 * [ExternalSystemException::class])` call sites do not multiply the retries.
 */
class NauSysRateLimitedException(
    val path: String,
    val attempts: Int,
) : ExternalSystemException(
        if (attempts == 0) {
            "NauSys concurrency gate timed out waiting for a slot on $path"
        } else {
            "NauSys 429 budget exhausted after $attempts attempts on $path"
        },
    )
