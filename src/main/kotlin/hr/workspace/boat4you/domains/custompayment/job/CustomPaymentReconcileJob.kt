package hr.workspace.boat4you.domains.custompayment.job

import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Safety net for missed Mollie webhooks of the custom `/pay` payments: every 10 min re-reads PENDING attempts
 * (younger than 48 h, not checked for 5 min), fails attempts whose order was never attached (15 min) and flags
 * attempts still pending after 48 h for review.
 *
 * Deliberately NOT `@Profile("data-sync")`: it runs where `MOLLIE_RECONCILE_ENABLED=true` is set — the API node
 * (cusma2), which holds the Mollie key and receives the webhooks. ShedLock keeps it single even if a second node
 * ever gets the flag.
 */
@Component
@ConditionalOnProperty(prefix = "application.mollie", name = ["reconcile-enabled"], havingValue = "true")
class CustomPaymentReconcileJob(
    private val customPaymentService: CustomPaymentService,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    @SchedulerLock(name = "mollieCustomPaymentReconcile", lockAtMostFor = "PT9M")
    fun run() {
        runCatching { customPaymentService.reconcileSweep() }
            .onFailure { log.error("Custom payment reconcile run failed", it) }
    }
}
