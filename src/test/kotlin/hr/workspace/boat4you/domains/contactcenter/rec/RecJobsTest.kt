package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The hourly housekeeping (P6 R1.4): purge, quota, and one warning mail a day once the recordings fill 70 % of their quota. */
class RecJobsTest {
    private var used = 0L
    private var purged = 0
    private val subjects = mutableListOf<String>()
    private val worker =
        mock(RecWorker::class.java) { inv ->
            when (inv.method.name) {
                "purge" -> purged++.let { 0 }
                "checkQuota" -> used
                "quotaBytes" -> 100L
                else -> null
            }
        }
    private val email =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") subjects += inv.arguments[1] as String
            null
        }
    private val jobs = RecJobs(worker, email, "info@example.com")

    private fun at(instant: String) {
        jobs.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
    }

    @Test
    fun `over 70 percent one warning a day, under it none, the purge runs every time`() {
        at("2026-11-01T10:17:00Z")
        used = 69
        jobs.housekeeping()
        subjects.size shouldBe 0
        used = 75
        jobs.housekeeping()
        at("2026-11-01T11:17:00Z")
        jobs.housekeeping()
        subjects.size shouldBe 1
        subjects.single() shouldContain "75 %"
        at("2026-11-02T00:17:00Z")
        jobs.housekeeping()
        subjects.size shouldBe 2
        purged shouldBe 4
    }
}
