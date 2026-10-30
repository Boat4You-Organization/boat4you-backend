package hr.workspace.boat4you.domains.calls.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface CallWebhookEventRepository : JpaRepository<CallWebhookEvent, Long> {
    /** Stores a delivery once per (conversation, type); returns the new id, or null when it was already stored. Call inside a transaction. */
    @Query(
        value =
            "INSERT INTO call_webhook_event (conversation_id, type, payload) VALUES (:conversationId, :type, CAST(:payload AS jsonb)) " +
                "ON CONFLICT (conversation_id, type) DO NOTHING RETURNING id",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("conversationId") conversationId: String,
        @Param("type") type: String,
        @Param("payload") payloadJson: String,
    ): Long?

    @Query("SELECT e FROM CallWebhookEvent e WHERE e.processedAt IS NULL AND e.receivedAt < :before ORDER BY e.receivedAt")
    fun findUnprocessedOlderThan(
        @Param("before") before: Instant,
    ): List<CallWebhookEvent>
}
