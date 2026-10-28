package hr.workspace.boat4you.domains.contactcenter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant

/** V9_103 on PostgreSQL 18 over the real chat + contact-center chain: the public visit key is unique and the open-visit indexes exist. */
class CcTrackerMigrationTest {
    private val jdbc = ContactCenterTestDb.ready()

    @Test
    fun `visit_key is unique and the open-visit indexes exist`() {
        val names = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'cc_visit'", String::class.java)
        names shouldContainAll listOf("uq_cc_visit_visit_key", "idx_cc_visit_open_visitor_site", "idx_cc_visit_open_last_seen")
        // P1a's idx_ai_chat_session_visitor (V9_102) serves the proactive lookup; V9_103 adds no chat index.
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'ai_chat_session' AND indexdef LIKE '%(visitor_id%'",
            Long::class.java,
        )!! shouldBeGreaterThanOrEqual 1L
        val key = "7d3f5e1a-0b6c-4e2a-9f10-2c3d4e5f6a7b"
        ccVisit(jdbc, "a0000000-0000-4000-8000-000000000001", "boat4you", Instant.parse("2026-10-26T10:00:00Z"), key)
        shouldThrow<DuplicateKeyException> {
            ccVisit(jdbc, "a0000000-0000-4000-8000-000000000002", "boat4you", Instant.parse("2026-10-26T10:00:00Z"), key)
        }
    }
}
