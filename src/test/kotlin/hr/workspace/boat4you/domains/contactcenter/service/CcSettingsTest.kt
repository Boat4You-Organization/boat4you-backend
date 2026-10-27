package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** The `cc.*` rows of `settings` on PostgreSQL 18 with the real V1_17 table: defaults, the 10 s cache, validation, the kill switch. */
class CcSettingsTest {
    private val jdbc = ContactCenterTestDb.ready()
    private var now = 0L
    private val settings = CcSettings(jdbc, Duration.ofSeconds(10)).also { it.ticker = { now } }

    private fun put(
        name: String,
        value: String?,
    ) = jdbc.update(
        "INSERT INTO settings (name, value, created, entity_status) VALUES (?, ?, now(), 'ACTIVE') " +
            "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value",
        name,
        value,
    )

    private fun later(seconds: Long) {
        now += Duration.ofSeconds(seconds).toNanos()
    }

    @Test
    fun `no rows means the defaults`() {
        settings.snapshot() shouldBe CcSettings.Snapshot(5, false, 90)
    }

    @Test
    fun `values are read, at most every 10 s`() {
        settings.snapshot()
        put("cc.handoff.minutes", "7")
        put("cc.backfill.enabled", "TRUE")
        put("cc.retention.ip.days", "180")
        later(5)
        settings.handoffMinutes shouldBe 5
        later(6)
        settings.snapshot() shouldBe CcSettings.Snapshot(7, true, 180)
    }

    @Test
    fun `invalid values fall back to the default`() {
        put("cc.handoff.minutes", "0")
        put("cc.backfill.enabled", "yes")
        put("cc.retention.ip.days", "99999")
        settings.snapshot() shouldBe CcSettings.Snapshot(5, false, 90)
        put("cc.handoff.minutes", "abc")
        put("cc.retention.ip.days", null)
        later(11)
        settings.snapshot() shouldBe CcSettings.Snapshot(5, false, 90)
    }

    @Test
    fun `disableBackfill switches the row off at once`() {
        put("cc.backfill.enabled", "true")
        settings.backfillEnabled shouldBe true
        settings.disableBackfill() shouldBe 1
        settings.backfillEnabled shouldBe false
        jdbc.queryForObject("SELECT value FROM settings WHERE name = 'cc.backfill.enabled'", String::class.java) shouldBe "false"
    }

    @Test
    fun `a failed read keeps the last values`() {
        put("cc.handoff.minutes", "9")
        settings.handoffMinutes shouldBe 9
        jdbc.execute("ALTER TABLE settings RENAME TO settings_gone")
        later(11)
        settings.handoffMinutes shouldBe 9
    }
}
