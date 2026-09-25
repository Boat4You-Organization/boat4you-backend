package hr.workspace.boat4you.domains.review.job

import hr.workspace.boat4you.domains.review.service.ReviewInvitationService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Daily review requests on the scheduler node (cusma3): YACHT requests for charters that ended 3-14 days ago, plus
 * the BOOKING catch-up for first payments of the last 48 h whose event-driven send did not happen.
 *
 * 09:10 UTC: a quiet slot — after the 09:00 birthday mail, before the 09:25 MMK reverify, the 09:32 pre-charter
 * reminder and the 09:40-09:50 trip jobs; morning in Europe, when review mails get opened. The candidate queries
 * touch a few hundred reservations at most; nothing runs on the API node.
 */
@Profile("data-sync")
@Component
class ReviewInvitationJob(
    private val invitationService: ReviewInvitationService,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 10 9 * * *", zone = "UTC")
    @SchedulerLock(name = "reviewInvitations", lockAtMostFor = "PT30M")
    fun run() {
        runCatching { invitationService.runDailySweep() }
            .onFailure { log.error("Review request sweep failed", it) }
    }
}
