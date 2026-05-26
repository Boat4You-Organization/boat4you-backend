package hr.workspace.boat4you.domains.invoice.job

import hr.workspace.boat4you.domains.invoice.services.InvoiceService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class GenerateInvoiceJob(
    private val invoiceService: InvoiceService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * This job runs every two hours, generates invoice entities
     */
    @Scheduled(cron = "0 7 0/2 ? * *")
    @SchedulerLock(name = "generateInvoice", lockAtMostFor = "PT1H")
    fun runJob() {
        log.info("Running GenerateInvoiceJob")
        val count = invoiceService.generateInvoicesFromJob()
        log.info("GenerateInvoiceJob generated $count invoices")
    }
}
