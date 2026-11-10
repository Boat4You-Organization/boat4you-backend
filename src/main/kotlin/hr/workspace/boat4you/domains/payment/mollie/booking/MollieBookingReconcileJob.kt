package hr.workspace.boat4you.domains.payment.mollie.booking

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Safety net for missed Mollie webhooks of online-booking card payments: every 10 min re-reads the PENDING ledger rows
 * old enough to matter (ReservationCardOrderRepository.findReconcileCandidateIds) and applies Mollie's answer. Runs
 * where `MOLLIE_RECONCILE_ENABLED=true` (cusma2, the node with the key and the webhooks); ShedLock keeps it single.
 */
@Component
@ConditionalOnProperty(prefix = "application.mollie", name = ["reconcile-enabled"], havingValue = "true")
class MollieBookingReconcileJob(
    private val service: MollieBookingPaymentService,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT3M")
    @SchedulerLock(name = "mollieBookingReconcile", lockAtMostFor = "PT9M")
    fun run() {
        runCatching { service.reconcileSweep() }
            .onFailure { log.error("Mollie booking reconcile run failed", it) }
    }
}
