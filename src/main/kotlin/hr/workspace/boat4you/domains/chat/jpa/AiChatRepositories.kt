package hr.workspace.boat4you.domains.chat.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface AiChatSessionRepository : JpaRepository<AiChatSession, Long> {
    fun findByTokenAndDeletedAtIsNull(token: String): AiChatSession?

    fun findAllByDeletedAtIsNullAndStatusInOrderByLastActivityAtDesc(statuses: Collection<String>, pageable: Pageable): List<AiChatSession>

    fun findAllByDeletedAtIsNullOrderByLastActivityAtDesc(pageable: Pageable): List<AiChatSession>

    fun findAllByDeletedAtIsNullAndFlaggedTrueOrderByLastActivityAtDesc(pageable: Pageable): List<AiChatSession>

    /** Sessions from one IP the guard locked since [after] - repeat offenders start locked (review B1.6). */
    fun countByIpAndFlaggedTrueAndLockedAtAfter(ip: String, after: Instant): Long

    fun countByStatusAndAdminUnreadTrue(status: String): Long

    /**
     * Stamps the first response of a conversation and nothing else. A conditional UPDATE instead of a save, because the
     * session object of a reply was loaded before the model ran and a save would write back every column (a takeover or
     * lock set meanwhile would be undone). Joins the caller's transaction; 0 rows when someone answered first.
     */
    @Transactional
    @Modifying
    @Query("UPDATE AiChatSession s SET s.firstResponseAt = :at, s.firstResponseBy = :by WHERE s.id = :id AND s.firstResponseAt IS NULL")
    fun stampFirstResponse(
        @Param("id") id: Long,
        @Param("at") at: Instant,
        @Param("by") by: String,
    )

    /** The holding stamp as one conditional UPDATE: 1 when this call stamped, 0 when the session no longer qualifies (ADMIN-8/9). */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.handoffHoldingAt = :at WHERE s.id = :id AND s.status = 'HUMAN_REQUESTED' " +
            "AND s.flagged = false AND s.deletedAt IS NULL AND s.handoffHoldingAt IS NULL",
    )
    fun stampHandoffHolding(
        @Param("id") id: Long,
        @Param("at") at: Instant,
    ): Int

    /** HUMAN -> AI as one conditional UPDATE; a flagged, LOCKED or deleted session is never returned (BACKEND-4, ADMIN-9). */
    @Transactional
    @Modifying
    @Query("UPDATE AiChatSession s SET s.status = 'AI', s.aiReturnedAt = :at WHERE s.id = :id AND s.status = 'HUMAN' AND s.flagged = false AND s.deletedAt IS NULL")
    fun returnToAi(
        @Param("id") id: Long,
        @Param("at") at: Instant,
    ): Int

    /** CcAiWatchdog restart recovery: recent conversations of one status; flagged and deleted ones are never touched. */
    fun findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndLastActivityAtAfter(
        status: String,
        after: Instant,
    ): List<AiChatSession>

    /** CcAiWatchdog holding sweep: handoffs requested inside [from, to] that have not had their holding message yet. */
    fun findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndHandoffHoldingAtIsNullAndHandoffRequestedAtBetween(
        status: String,
        from: Instant,
        to: Instant,
    ): List<AiChatSession>
}

interface AiChatMessageRepository : JpaRepository<AiChatMessage, Long> {
    fun findAllBySessionIdOrderByIdAsc(sessionId: Long): List<AiChatMessage>

    fun findAllBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId: Long, afterId: Long): List<AiChatMessage>

    fun countBySessionId(sessionId: Long): Long

    fun deleteAllBySessionId(sessionId: Long)

    fun findBySessionIdAndClientMessageId(
        sessionId: Long,
        clientMessageId: String,
    ): AiChatMessage?

    /** The newest message of a session (restart recovery: is the last one still an unanswered visitor line). */
    fun findFirstBySessionIdOrderByIdDesc(sessionId: Long): AiChatMessage?
}
