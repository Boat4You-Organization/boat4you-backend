package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/** `calls.*` rows of the settings table (design §2.5, §6.4): defaults, parsing, validation on write. */
class CallsSettingsTest {
    private val jdbc = ContactCenterTestDb.newDatabase().also { ContactCenterTestDb.apply(it, listOf("V1_17__add_settings.sql")) }
    private val settings = CallsSettings(jdbc, Duration.ofSeconds(10))

    private fun row(
        name: String,
        value: String,
    ) = jdbc.update("INSERT INTO settings (name, value, created, entity_status) VALUES (?, ?, now(), 'ACTIVE')", name, value)

    @Test
    fun `an empty table gives the design defaults with calls switched off`() {
        val s = settings.snapshot()
        s.enabled shouldBe false
        s.testNumbers shouldBe emptySet()
        s.windowStart shouldBe LocalTime.of(9, 0)
        s.windowEnd shouldBe LocalTime.of(19, 0)
        s.firstDelay shouldBe Duration.ofMinutes(5)
        s.followUpDelay shouldBe Duration.ofHours(48)
        s.maxAttempts shouldBe 3
        s.transferDays shouldBe setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        s.transferStart shouldBe LocalTime.of(9, 0)
        s.transferEnd shouldBe LocalTime.of(16, 0)
        s.transferAvailable shouldBe true
        s.transferNumber.shouldBeNull()
        s.dailyLimit shouldBe 40
        s.monthlyLimit shouldBe 400
        s.audioRetentionDays shouldBe 30
    }

    @Test
    fun `rows are parsed, test numbers are E164 and trimmed`() {
        row("calls.enabled", "true")
        row("calls.test.numbers", "+385912345678, +12125550100")
        val s = settings.snapshot()
        s.enabled shouldBe true
        s.testNumbers shouldBe setOf("+385912345678", "+12125550100")
    }

    @Test
    fun `an invalid stored value falls back to the default`() {
        row("calls.window.start", "25:00")
        row("calls.limit.daily", "-3")
        settings.snapshot().windowStart shouldBe LocalTime.of(9, 0)
        settings.snapshot().dailyLimit shouldBe 40
    }

    @Test
    fun `write refuses an invalid value and names the key`() {
        val e = shouldThrow<ParameterValidationException> { settings.write(mapOf("calls.limit.daily" to "0")) }
        e.badOrMissingParameters shouldContainKey "calls.limit.daily"
        shouldThrow<ParameterValidationException> { settings.write(mapOf("calls.unknown" to "1")) }
        shouldThrow<ParameterValidationException> { settings.write(mapOf("calls.window.start" to "20:00")) } // start after end
    }

    @Test
    fun `write stores the value and the snapshot sees it at once`() {
        settings.write(mapOf("calls.limit.daily" to "50", "calls.transfer.number" to "+385981234567"))
        settings.snapshot().dailyLimit shouldBe 50
        settings.snapshot().transferNumber shouldBe "+385981234567"
        settings.write(mapOf("calls.limit.daily" to "60"))
        settings.snapshot().dailyLimit shouldBe 60
        jdbc.queryForObject("SELECT count(*) FROM settings WHERE name = 'calls.limit.daily'", Int::class.java) shouldBe 1
    }
}
