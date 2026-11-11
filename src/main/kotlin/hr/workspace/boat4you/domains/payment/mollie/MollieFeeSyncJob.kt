package hr.workspace.boat4you.domains.payment.mollie

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hourly: Mollie's fee of the paid payments that do not have it yet ([MollieFeeSyncService]). Runs where the other
 * Mollie jobs run (`MOLLIE_RECONCILE_ENABLED=true`, cusma2); does nothing until the organisation token is set.
 */
@Component
@ConditionalOnProperty(prefix = "application.mollie", name = ["reconcile-enabled"], havingValue = "true")
class MollieFeeSyncJob(
    private val service: MollieFeeSyncService,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT4M")
    @SchedulerLock(name = "mollieFeeSync", lockAtMostFor = "PT30M")
    fun run() {
        runCatching { service.sync() }
            .onFailure { log.error("Mollie fee sync failed: {}", it.toString()) }
    }
}
