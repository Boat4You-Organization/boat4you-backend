package hr.workspace.boat4you.domains.external.nausys.reverify

import hr.workspace.boat4you.domains.catalouge.enums.OPTION_ECHO_GRACE_HOURS
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.service.ExternalAvailabilityReconcileService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B2 hook: [ExternalAvailabilityReconcileService.purgeExpiredOptions] queues the NauSys OPTION offers under
 * the lapsed holds (priority 2) BEFORE the mirror rows are deleted, with the purge's own grace cutoff. The SQL itself
 * is covered in NauSysFlipLogIntegrationTest.
 */
class NauSysPurgeHookTests {
    private val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()

    private fun <T> recording(type: Class<T>): T =
        mock(type) { inv ->
            calls += inv.method.name to inv.arguments.toList()
            if (inv.method.returnType == Int::class.javaPrimitiveType) 0 else null
        }

    @Test
    fun `lapsed NauSys holds queue their OPTION offers for a re-check before the purge deletes them`() {
        val service =
            ExternalAvailabilityReconcileService(
                recording(ExternalReservationRepository::class.java),
                recording(ExternalMappingRepository::class.java),
                recording(OfferRepository::class.java),
                true,
            )

        service.purgeExpiredOptions()

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
    }
}
