package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.reservation.service.TripPhotoService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * GDPR photo retention (Mario 5.7.2026): trip photos live for 10 days after the
 * charter ends, then we delete them from the system — rows + NFS files. The
 * crew is warned of the exact deadline in the T+1 "album ready" concierge post,
 * so this purge is silent. Daily at 09:50 (after the push 09:40 and concierge
 * automation 09:45), on the scheduler node.
 */
@Profile("data-sync")
@Component
class TripPhotoRetentionJob(
    private val tripPhotoService: TripPhotoService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 50 9 ? * *")
    @SchedulerLock(name = "tripPhotoRetention", lockAtMostFor = "PT30M")
    fun run() {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS).atStartOfDay()
        val purged = tripPhotoService.purgeExpired(cutoff)
        if (purged > 0) log.info("TripPhotoRetentionJob: purged $purged trip photo(s) past the $RETENTION_DAYS-day retention")
    }

    companion object {
        private const val RETENTION_DAYS = 10L
    }
}
