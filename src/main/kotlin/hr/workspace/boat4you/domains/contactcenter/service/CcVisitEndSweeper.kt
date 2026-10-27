package hr.workspace.boat4you.domains.contactcenter.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock

/**
 * Every 5 minutes, on the scheduler node (`data-sync` profile, one run at a time across nodes), closes the visits that were silent for
 * more than [VisitService.RESUME_WINDOW]: `ended_at` becomes the visit's own `last_seen_at`. A visit is also closed when its visitor
 * starts a new one on the same site; this sweep catches those that never came back.
 */
@Profile("data-sync")
@Component
class CcVisitEndSweeper(
    private val jdbc: JdbcTemplate,
) {
    internal var clock: Clock = Clock.systemUTC()

    /** Returns Unit on purpose: ShedLock 5.16 refuses to lock a method that returns a JVM primitive, which a Kotlin Int is. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @SchedulerLock(name = "ccVisitEndSweep", lockAtMostFor = "PT4M")
    fun sweep() {
        sweepOnce()
    }

    /** Closes the idle visits and returns how many. */
    internal fun sweepOnce(): Int =
        jdbc.update(
            "UPDATE cc_visit SET ended_at = last_seen_at WHERE ended_at IS NULL AND last_seen_at < ?",
            Timestamp.from(clock.instant().minus(VisitService.RESUME_WINDOW)),
        )
}
