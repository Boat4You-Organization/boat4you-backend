package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * GDPR Art. 5(1)(e) (storage limitation) for inquiry leads (audit B3).
 *
 * Inquiries are name/email/phone/message submitted by anonymous visitors via
 * the public contact form. They carry no accounting or partner-reconciliation
 * obligation (unlike reservations), so they must not be kept indefinitely.
 * This daily cron hard-deletes inquiries older than `inquiry.retention-months`
 * (default 24). ShedLock keeps the two scheduler VMs from both firing.
 */
@Profile("data-sync")
@Component
class InquiryRetentionJob(
    private val inquiryRepository: InquiryRepository,
    @Value("\${inquiry.retention-months:24}") private val retentionMonths: Long,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "inquiryRetentionPurge", lockAtMostFor = "PT30M")
    @Transactional
    fun purgeOldInquiries() {
        val cutoff = LocalDateTime.now().minusMonths(retentionMonths)
        val deleted = inquiryRepository.deleteByCreatedAtBefore(cutoff)
        if (deleted > 0) {
            log.info(
                "Inquiry retention purge: deleted {} inquiry lead(s) older than {} months (before {})",
                deleted,
                retentionMonths,
                cutoff,
            )
        }
    }
}
