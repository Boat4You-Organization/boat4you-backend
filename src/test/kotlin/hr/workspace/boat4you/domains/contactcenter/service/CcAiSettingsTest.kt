package hr.workspace.boat4you.domains.contactcenter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The `cc.*` AI settings: documented defaults, validation, and what an admin may write. */
class CcAiSettingsTest {
    @Test
    fun `no rows - the documented defaults`() {
        CcAiSettings.parse(emptyMap()) shouldBe CcAiSettings.Values(5, null, 50, 20, 5, 30, 10, 3, 5)
    }

    @Test
    fun `invalid values fall back to the defaults`() {
        val v =
            CcAiSettings.parse(
                mapOf("cc.handoff.minutes" to "0", "cc.breaker.global.messages.per.min" to "abc", "cc.breaker.cooldown.minutes" to "61"),
            )
        v shouldBe CcAiSettings.Values()
    }

    @Test
    fun `valid values are used, a blank takeover value means off`() {
        CcAiSettings.parse(mapOf("cc.handoff.minutes" to " 7 ", "cc.takeover.return.minutes" to "30")).let {
            it.handoffMinutes shouldBe 7
            it.takeoverReturnMinutes shouldBe 30
        }
        CcAiSettings.parse(mapOf("cc.takeover.return.minutes" to "")).takeoverReturnMinutes shouldBe null
    }

    @Test
    fun `what an admin may write`() {
        CcAiSettings.isAllowed("cc.takeover.return.minutes", null) shouldBe true
        CcAiSettings.isAllowed("cc.handoff.minutes", null) shouldBe false
        CcAiSettings.isAllowed("cc.handoff.minutes", "121") shouldBe false
        CcAiSettings.isAllowed("cc.unknown", "1") shouldBe false
        CcAiSettings.SPECS.keys shouldBe
            setOf(
                "cc.handoff.minutes",
                "cc.takeover.return.minutes",
                "cc.breaker.global.messages.per.min",
                "cc.breaker.global.sessions.per.min",
                "cc.breaker.global.locks.per.min",
                "cc.breaker.site.messages.per.min",
                "cc.breaker.site.sessions.per.min",
                "cc.breaker.site.locks.per.min",
                "cc.breaker.cooldown.minutes",
            )
    }
}
