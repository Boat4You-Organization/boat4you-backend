package hr.workspace.boat4you.domains.voucher.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly sweep flipping overdue ACTIVE vouchers to EXPIRED. Belt-and-braces:
 * validation and redemption both check valid_to themselves — this keeps the
 * table honest for reporting and any future "my vouchers" listing.
 */
@Profile("data-sync")
@Component
class VoucherExpiryJob(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "0 25 3 * * *")
    @SchedulerLock(name = "voucherExpiry", lockAtMostFor = "PT10M")
    fun expireOverdueVouchers() {
        val expired = jdbcTemplate.update(
            "UPDATE voucher SET status = 'EXPIRED', updated_at = now() WHERE status = 'ACTIVE' AND valid_to < CURRENT_DATE",
        )

        if (expired > 0) log.info("Voucher expiry: marked {} vouchers EXPIRED", expired)
    }
}
