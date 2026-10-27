package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** The missed-conversation stamp on PostgreSQL 18 with the real V9_102 columns: no human reply within `cc.handoff.minutes` of the request. */
class MissedConversationMarkerTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val marker = MissedConversationMarker(jdbc, CcSettings(jdbc, Duration.ZERO))

    private fun session(
        handoffAgo: String?,
        humanAfter: String? = null,
        missed: Boolean = false,
    ): Long {
        val id =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, handoff_requested_at, missed) " +
                    "VALUES (gen_random_uuid()::text, now() - CAST(? AS interval), ?) RETURNING id",
                Long::class.java,
                handoffAgo,
                missed,
            )!!
        humanAfter?.let {
            jdbc.update("UPDATE ai_chat_session SET first_human_response_at = handoff_requested_at + CAST(? AS interval) WHERE id = ?", it, id)
        }
        return id
    }

    private fun missed() = jdbc.queryForList("SELECT id FROM ai_chat_session WHERE missed", Long::class.java).toSet()

    @Test
    fun `no human reply within cc_handoff_minutes (default 5) marks the conversation missed`() {
        val late = session("6 minutes")
        session("4 minutes")
        session("10 minutes", humanAfter = "2 minutes")
        val answeredLate = session("20 minutes", humanAfter = "7 minutes")
        session(null)
        val already = session("1 hour", missed = true)
        marker.markMissed() shouldBe 2
        missed() shouldBe setOf(late, answeredLate, already)
    }

    @Test
    fun `the threshold comes from the setting`() {
        jdbc.update("INSERT INTO settings (name, value, created, entity_status) VALUES ('cc.handoff.minutes', '15', now(), 'ACTIVE')")
        session("6 minutes")
        session("20 minutes", humanAfter = "7 minutes")
        val late = session("16 minutes")
        marker.markMissed() shouldBe 1
        missed() shouldBe setOf(late)
    }
}
