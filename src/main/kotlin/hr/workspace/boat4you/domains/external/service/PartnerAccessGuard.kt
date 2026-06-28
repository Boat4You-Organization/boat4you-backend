package hr.workspace.boat4you.domains.external.service

import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agency back-off for partner sync (MMK + NauSys).
 *
 * Partners constantly on/off-board charter agencies — daily. When an agency is
 * removed (or stops paying the partner) the partner starts rejecting our calls
 * for it: MMK returns HTTP 400 "Illegal access to entity". Without a guard the
 * scheduler re-attempts every such agency on every run (availability syncs run
 * several times a day, for multiple years each), flooding ERROR logs so the
 * server monitoring goes "red" and burning HTTP/CPU/DB connections on data we
 * will never get.
 *
 * Rule (Mario 28.6.2026): try once; on a hard refusal don't retry; if an agency
 * keeps refusing, after [GIVE_UP_AFTER] consecutive failures stop calling it.
 * Re-probe once a day so a returned / re-added agency heals automatically, and
 * a single success clears it immediately.
 *
 * State is in-memory per scheduler process — intentional: it resets on deploy,
 * needs no DB migration, and the worst case after a restart is one extra probe
 * cycle per dead agency. Keyed by (externalSystemId, agencyExternalId) so MMK
 * and NauSys are tracked independently.
 */
@Component
class PartnerAccessGuard {
    private data class Strike(val count: Int, val since: Instant)

    private val strikes = ConcurrentHashMap<String, Strike>()

    val giveUpThreshold: Int = GIVE_UP_AFTER

    /** True once an agency has failed [GIVE_UP_AFTER]× and the daily re-probe window hasn't elapsed. */
    fun shouldSkip(externalSystemId: Long, agencyExternalId: Long): Boolean {
        val k = key(externalSystemId, agencyExternalId)
        val s = strikes[k] ?: return false
        if (Duration.between(s.since, Instant.now()) >= REPROBE_AFTER) {
            strikes.remove(k) // daily re-probe — give it another chance
            return false
        }
        return s.count >= GIVE_UP_AFTER
    }

    /** Record a failed attempt; returns the new consecutive-strike count. */
    fun recordFailure(externalSystemId: Long, agencyExternalId: Long): Int =
        strikes.compute(key(externalSystemId, agencyExternalId)) { _, cur ->
            if (cur == null || Duration.between(cur.since, Instant.now()) >= REPROBE_AFTER) {
                Strike(1, Instant.now())
            } else {
                cur.copy(count = cur.count + 1)
            }
        }!!.count

    /** A success clears the agency's back-off. */
    fun recordSuccess(externalSystemId: Long, agencyExternalId: Long) {
        strikes.remove(key(externalSystemId, agencyExternalId))
    }

    /** MMK returns HTTP 400 "Illegal access to entity" for agencies it no longer serves. */
    fun isAccessDenied(e: Throwable?): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is HttpClientErrorException &&
                cause.statusCode.value() == 400 &&
                cause.responseBodyAsString.contains("Illegal access", ignoreCase = true)
            ) {
                return true
            }
            if (cause.message?.contains("Illegal access to entity", ignoreCase = true) == true) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun key(externalSystemId: Long, agencyExternalId: Long) = "$externalSystemId:$agencyExternalId"

    companion object {
        private const val GIVE_UP_AFTER = 2
        private const val MAX_CAUSE_DEPTH = 6
        private val REPROBE_AFTER: Duration = Duration.ofHours(24)
    }
}
