package hr.workspace.boat4you.domains.external.nausys.reverify

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.catalouge.enums.OPTION_ECHO_GRACE_HOURS
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B2 hook: [ExternalAvailabilityReconcileService.purgeExpiredOptions] queues the NauSys OPTION offers under
 * the lapsed holds (priority 2) BEFORE the mirror rows are deleted, with the purge's own grace cutoff - only while
 * NAUSYS_REVERIFY is not OFF, in its own REQUIRES_NEW transaction, and a failure never stops the purge. The SQL itself
 * is covered in NauSysFlipLogIntegrationTest.
 */
class NauSysPurgeHookTests {
    private val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    private var hookFails = false

    private fun <T> recording(type: Class<T>): T =
        mock(type) { inv ->
            calls += inv.method.name to inv.arguments.toList()
            if (inv.method.name == "queueNauSysOptionsUnderExpiringHolds" && hookFails) error("statement timeout")
            if (inv.method.name == "queueNauSysOptionsUnderExpiringHolds") 3 else if (inv.method.returnType == Int::class.javaPrimitiveType) 0 else null
        }

    private val txCalls = CopyOnWriteArrayList<String>()
    private val txManager =
        mock(PlatformTransactionManager::class.java) { inv ->
            when (inv.method.name) {
                "getTransaction" -> {
                    txCalls += "begin propagation=" + (inv.arguments[0] as TransactionDefinition).propagationBehavior
                    SimpleTransactionStatus()
                }
                "commit" -> {
                    txCalls += "commit"
                    null
                }
                "rollback" -> {
                    txCalls += "rollback"
                    null
                }
                else -> null
            }
        }

    private fun service(mode: NauSysReverifyMode) =
        ExternalAvailabilityReconcileService(
            recording(ExternalReservationRepository::class.java),
            recording(ExternalMappingRepository::class.java),
            recording(OfferRepository::class.java),
            true,
            mock(RealPriceSwitches::class.java) { inv -> if (inv.method.name == "getNausysReverify") mode else null },
            txManager,
        )

    @Test
    fun `lapsed NauSys holds queue their OPTION offers for a re-check before the purge deletes them`() {
        service(NauSysReverifyMode.LOG).purgeExpiredOptions()

        val names = calls.map { it.first }
        val queue = names.indexOf("queueNauSysOptionsUnderExpiringHolds")
        (queue >= 0) shouldBe true
        (queue < names.indexOf("deleteExpiredOptions")) shouldBe true
        val args = calls[queue].second
        args[0] shouldBe 2L
        val cutoff = args[1] as LocalDateTime
        (Duration.between(cutoff, LocalDateTime.now().minusHours(OPTION_ECHO_GRACE_HOURS)).abs() < Duration.ofSeconds(5)) shouldBe true
        args[2] shouldBe PeriodCheckPriority.STALE_OPTION
        // the purge itself is unchanged: the same cutoff for both deletes
        calls.filter { it.first == "deleteExpiredOptions" }.map { it.second[1] }.toSet() shouldBe setOf(cutoff)
        // its own transaction, committed on its own
        txCalls shouldBe listOf("begin propagation=${TransactionDefinition.PROPAGATION_REQUIRES_NEW}", "commit")
    }

    @Test
    fun `NAUSYS_REVERIFY OFF (the default) runs no hook - the purge is today's`() {
        service(NauSysReverifyMode.OFF).purgeExpiredOptions()

        calls.none { it.first == "queueNauSysOptionsUnderExpiringHolds" } shouldBe true
        txCalls shouldBe emptyList()
        calls.count { it.first == "deleteExpiredOptions" } shouldBe 2
    }

    @Test
    fun `a failing hook rolls back only itself, the purge goes on`() {
        hookFails = true

        service(NauSysReverifyMode.WRITE).purgeExpiredOptions()

        txCalls shouldBe listOf("begin propagation=${TransactionDefinition.PROPAGATION_REQUIRES_NEW}", "rollback")
        calls.count { it.first == "deleteExpiredOptions" } shouldBe 2
        calls.any { it.first == "deleteOrphanReservationMappings" } shouldBe true
    }
}
