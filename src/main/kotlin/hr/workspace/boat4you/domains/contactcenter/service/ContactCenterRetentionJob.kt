package hr.workspace.boat4you.domains.contactcenter.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [ContactCenterRetention] nightly at 03:45 on the scheduler node (`data-sync` profile, one run at a time across nodes),
 * after `InquiryRetentionJob` (03:30) and `RetentionReaperJob` (03:40). Logs the counts at INFO when anything changed.
 */
@Profile("data-sync")
@Component
class ContactCenterRetentionJob(
    private val retention: ContactCenterRetention,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 45 3 * * *")
    @SchedulerLock(name = "ccRetentionPurge", lockAtMostFor = "PT30M")
    fun run() {
        val result = retention.purge()
        if (result != NOTHING) log.info("Contact center retention: {}", result)
    }

    private companion object {
        val NOTHING = ContactCenterRetention.Result(0, 0, 0, 0, 0, 0, 0, 0)
    }
}
