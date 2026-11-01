package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.util.Locale

/**
 * The recording worker's schedule on the scheduler node (P6 R1.4): every minute the spool and the closes, every hour the purge
 * of files older than 60 days and the quota, with one warning mail a day once the recordings use [WARN_PERCENT] % of it.
 */
@Profile("data-sync")
@Component
class RecJobs(
    private val worker: RecWorker,
    private val assets: RecAssetArchiver,
    private val email: EmailService,
    @Value("\${cc.rec.alert-email:info@boat4you.com}") private val alertEmail: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var clock: Clock = Clock.systemUTC()

    @Volatile private var warnedOn: LocalDate? = null

    @Scheduled(cron = "30 * * * * *", zone = "UTC")
    @SchedulerLock(name = "recWorker", lockAtMostFor = "PT10M")
    fun work() {
        runCatching { worker.runOnce() }
            .onSuccess { if (it.payloads + it.closed + it.dropped > 0) log.info("rec worker: {}", it) }
            .onFailure { log.error("rec worker failed", it) }
    }

    @Scheduled(cron = "0 17 * * * *", zone = "UTC")
    @SchedulerLock(name = "recHousekeeping", lockAtMostFor = "PT30M")
    fun housekeeping() {
        runCatching { worker.purge() }
            .onSuccess { if (it > 0) log.info("rec purge: {} recording files older than the retention deleted", it) }
            .onFailure { log.error("rec purge failed", it) }
        // An asset is touched by every recording that closes with it: one unused for the retention and a week is no longer needed.
        runCatching { assets.purgeUnused(ASSET_UNUSED_DAYS) }
            .onSuccess { if (it > 0) log.info("rec assets: {} unused files deleted", it) }
            .onFailure { log.error("rec asset purge failed", it) }
        val used = runCatching { worker.checkQuota() }.onFailure { log.error("rec quota check failed", it) }.getOrNull() ?: return
        val quota = worker.quotaBytes()
        val percent = if (quota > 0) used * 100 / quota else 100
        val today = LocalDate.now(clock)
        if (percent < WARN_PERCENT || warnedOn == today) return
        warnedOn = today
        runCatching {
            email.sendEmail(
                recipients = listOf(alertEmail),
                subject = "Snimke posjeta: disk $percent % (${gb(used)} od ${gb(quota)})",
                templateName = "email/ccRecQuota",
                variables =
                    mapOf(
                        "headline" to "Snimke posjeta: disk se puni",
                        "used" to gb(used),
                        "quota" to gb(quota),
                        "percent" to percent,
                        "adminUrl" to ADMIN_URL,
                        "currentYear" to Year.now(ZoneOffset.UTC).value,
                    ),
            )
        }.onFailure { log.warn("rec quota warning mail failed: {}", it.toString()) }
    }

    private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / BYTES_PER_GB)

    companion object {
        const val WARN_PERCENT = 70
        private const val ASSET_UNUSED_DAYS = 67L
        private const val BYTES_PER_GB = 1_073_741_824.0
        private const val ADMIN_URL = "https://admin.boat4you.com/contact-center"
    }
}
