package hr.workspace.boat4you.common.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * ShedLock configuration for the @Scheduled cron methods across the
 * codebase. See V1_92 migration for the data-store rationale.
 *
 * `defaultLockAtMostFor = "PT2H"` is the safety net: if a VM grabs a
 * lock and then dies without releasing it, the row's lock_until is at
 * most 2 hours in the future, after which another VM may claim and
 * re-run. Long-running catalogue syncs override this on the
 * `@SchedulerLock` annotation itself (NauSys yacht sync runs ~30-90 min
 * in the wild — F4-005). Short jobs do not override.
 *
 * `defaultLockAtLeastFor = "PT0S"` means a fast-finishing job does not
 * hold the lock past completion; we want crons to be resumable in case
 * a downstream failure means a retry on the next cron tick is OK.
 * Specific state-changing jobs that must not re-fire within their own
 * cron interval set a non-zero `lockAtLeastFor` on their annotation.
 *
 * `usingDbTime()` on the provider makes lock-until comparisons happen
 * against the Postgres clock, not the JVM clock — VM2 and VM3 may drift
 * by seconds without NTP slip, and clock-skew races on lock acquisition
 * are an entire category of bug we never need to debug.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2H", defaultLockAtLeastFor = "PT0S")
class SchedulerLockConfig {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}
