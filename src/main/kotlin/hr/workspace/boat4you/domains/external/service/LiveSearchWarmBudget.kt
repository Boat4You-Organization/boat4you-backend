package hr.workspace.boat4you.domains.external.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Per-JVM budget of the live partner-2 (NauSys) part of the search warm while `NAUSYS_WARM_LONG_RANGES=ON`
 * (DESIGN.md B3). The longer ranges that switch lets through add an estimated +200-300 live freeYachtsSearch calls
 * a day on the API node; this keeps the worst case bounded:
 * - a daily cap ([dailyCap], `NAUSYS_LIVE_WARM_DAILY_CAP`, default 1200) of live calls per JVM and UTC day;
 * - a circuit: [CIRCUIT_TRIP_COUNT] rate-limit failures within [CIRCUIT_WINDOW] stop live calls for [CIRCUIT_OPEN_FOR].
 *
 * A warm that is not allowed is parked in `nausys_search_sync_retry` by the caller and replayed on the scheduler
 * node; the API node never retries the partner itself (rule of 5.9.2026). In-memory only: a restart resets both.
 *
 * Log lines name no partner on purpose: the cusma3 restart gate greps the journal for partner names as sync activity.
 */
@Component
class LiveSearchWarmBudget(
    @Value("\${application.external.nausys.live-warm-daily-cap:1200}") private val dailyCap: Int,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /** UTC clock; replaced in tests. */
    internal var clock: Clock = Clock.systemUTC()

    enum class Decision {
        /** Call the partner now (counted against the daily cap). */
        CALL,

        /** The daily cap is spent: park the warm for the scheduler. */
        DAILY_CAP,

        /** The rate-limit circuit is open: park the warm for the scheduler. */
        CIRCUIT_OPEN,
    }

    private var day: LocalDate? = null
    private var callsToday = 0
    private var capLoggedOn: LocalDate? = null
    private val recentRateLimits = ArrayDeque<Instant>()
    private var openUntil: Instant? = null

    /** Decides one live warm; a [Decision.CALL] is counted against today's cap. */
    @Synchronized
    fun tryAcquire(): Decision {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (today != day) {
            day = today
            callsToday = 0
        }
        val decision =
            when {
                circuitOpen(now) -> Decision.CIRCUIT_OPEN
                callsToday < dailyCap -> Decision.CALL
                else -> Decision.DAILY_CAP
            }
        if (decision == Decision.CALL) callsToday++
        if (decision == Decision.DAILY_CAP && capLoggedOn != today) {
            capLoggedOn = today
            log.warn("Live search warm budget: daily cap {} reached for {} (UTC), further warms go to the scheduler queue", dailyCap, today)
        }
        return decision
    }

    /** Called with the monitor held; closes an expired circuit. */
    private fun circuitOpen(now: Instant): Boolean {
        val open = openUntil
        val stillOpen = open != null && now.isBefore(open)
        if (open != null && !stillOpen) {
            openUntil = null
            log.info("Live search warm budget: rate-limit circuit closed, live calls resume")
        }
        return stillOpen
    }

    /** Records one rate-limit failure of a live warm; the [CIRCUIT_TRIP_COUNT]th within [CIRCUIT_WINDOW] opens the circuit. */
    @Synchronized
    fun recordRateLimited() {
        val now = clock.instant()
        recentRateLimits.addLast(now)
        val windowStart = now.minus(CIRCUIT_WINDOW)
        while (recentRateLimits.isNotEmpty() && recentRateLimits.first().isBefore(windowStart)) recentRateLimits.removeFirst()
        if (recentRateLimits.size >= CIRCUIT_TRIP_COUNT) {
            recentRateLimits.clear()
            openUntil = now.plus(CIRCUIT_OPEN_FOR)
            log.warn(
                "Live search warm budget: {} rate-limit failures within {} min, circuit open until {}",
                CIRCUIT_TRIP_COUNT,
                CIRCUIT_WINDOW.toMinutes(),
                openUntil,
            )
        }
    }

    /** Live calls counted for the current UTC day (for tests and diagnostics). */
    @Synchronized
    fun callsToday(): Int = if (day == LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)) callsToday else 0

    /** Thrown into the retry queue's `last_error` when a warm is parked instead of called. */
    class DeferredException(
        decision: Decision,
    ) : RuntimeException("live search warm deferred to the scheduler: $decision")

    companion object {
        const val CIRCUIT_TRIP_COUNT = 5
        val CIRCUIT_WINDOW: Duration = Duration.ofMinutes(10)
        val CIRCUIT_OPEN_FOR: Duration = Duration.ofMinutes(15)
    }
}
