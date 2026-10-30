package hr.workspace.boat4you.domains.contactcenter.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Gives old IRIS conversations their contacts (P2), on the scheduler node, one run at a time across nodes. Each run walks
 * every unlinked session with a name, email or phone once; the ones it cannot link (a refused name only) are looked at again
 * next run, which stays cheap because everything else is linked by then. New conversations are linked when the admin opens them.
 */
@Profile("data-sync")
@Component
class ContactLinkJob(
    private val linker: ContactLinker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cc.contacts.link-ms:300000}", initialDelayString = "\${cc.contacts.link-ms:300000}")
    @SchedulerLock(name = "ccContactLink", lockAtMostFor = "PT1H")
    fun run() {
        var afterId = 0L
        var linked = 0
        do {
            val pass = linker.linkPending(afterId)
            linked += pass.linked
            afterId = pass.lastId
        } while (pass.scanned == ContactLinker.BATCH)
        if (linked > 0) log.info("CC contacts: {} conversations linked to a contact", linked)
    }
}
