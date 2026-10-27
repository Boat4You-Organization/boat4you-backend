package hr.workspace.boat4you.domains.contactcenter.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Runs [MissedConversationMarker] every minute on the scheduler node (`data-sync` profile, one run at a time across nodes). */
@Profile("data-sync")
@Component
class MissedConversationJob(
    private val marker: MissedConversationMarker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cc.missed.poll-ms:60000}", initialDelayString = "\${cc.missed.poll-ms:60000}")
    @SchedulerLock(name = "ccMissedConversations", lockAtMostFor = "PT5M")
    fun run() {
        val marked = marker.markMissed()
        if (marked > 0) log.info("Missed conversations: {} newly marked", marked)
    }
}
