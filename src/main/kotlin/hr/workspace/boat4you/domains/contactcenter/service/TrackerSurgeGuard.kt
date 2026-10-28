package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two surge limits of the tracker that are not per IP (design 2.6, "po stranici"):
 *
 * - a database bulkhead: at most [MAX_CONCURRENT] tracker requests run their service call at once, so a flood of beats can never
 *   hold the connections that booking and the server-rendered pages share (the cusma2 Hikari pool is 35, `DB_POOL_MAX`; the default is 25);
 *   a request waits [ENTER_WAIT] for a permit and is then refused ([tryEnter] false, the controller answers 503);
 * - a visit budget per site: at most `siteVisitsPerMinute` calls of `/visit` per site in each clock minute, so one flooded site
 *   never uses up the budget of the others. A refusal is counted for the security log ([drainRefusedVisits]).
 */
@Component
class TrackerSurgeGuard(
    @Value("\${application.rate-limit.cc-site-visits.per-minute:300}") private val siteVisitsPerMinute: Int,
) {
    internal var clock: Clock = Clock.systemUTC()

    private class Window(
        val minute: Long,
    ) {
        val used = AtomicInteger()
    }

    private val permits = Semaphore(MAX_CONCURRENT)

    /** Callers pass validated site ids, so each of the two maps holds at most 7 keys. */
    private val windows = ConcurrentHashMap<String, Window>()
    private val refused = ConcurrentHashMap<String, AtomicInteger>()

    /** True when the caller may use the database now (and must call [exit] afterwards), false after waiting [ENTER_WAIT] in vain. */
    fun tryEnter(): Boolean = permits.tryAcquire(ENTER_WAIT.toMillis(), TimeUnit.MILLISECONDS)

    fun exit() = permits.release()

    /** True while [siteId] has visits left in the current clock minute; a refusal is counted. */
    fun allowVisit(siteId: String): Boolean {
        val minute = clock.instant().epochSecond / SECONDS_PER_MINUTE
        val window = windows.compute(siteId) { _, w -> if (w == null || w.minute != minute) Window(minute) else w }!!
        if (window.used.getAndUpdate { if (it < siteVisitsPerMinute) it + 1 else it } < siteVisitsPerMinute) return true
        refused.computeIfAbsent(siteId) { AtomicInteger() }.incrementAndGet()
        return false
    }

    /** The visits refused per site since the last drain; the counts start again from zero. */
    fun drainRefusedVisits(): Map<String, Int> = refused.entries.mapNotNull { (siteId, n) -> n.getAndSet(0).takeIf { it > 0 }?.let { siteId to it } }.toMap()

    companion object {
        const val MAX_CONCURRENT = 8
        val ENTER_WAIT: Duration = Duration.ofMillis(100)
        private const val SECONDS_PER_MINUTE = 60L
    }
}
