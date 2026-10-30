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

    /**
     * The broker takes a conversation without writing (P2 "Preuzmi"): HUMAN, the takeover stamped once. Never a LOCKED or
     * deleted one (ADMIN-9: a locked conversation is answered with a reply). 1 when taken, 0 otherwise; HUMAN again is 1.
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.status = 'HUMAN', s.takenOverAt = COALESCE(s.takenOverAt, :at) " +
            "WHERE s.id = :id AND s.status IN ('AI', 'HUMAN_REQUESTED', 'HUMAN', 'CLOSED') AND s.deletedAt IS NULL",
    )
    fun takeOver(
        @Param("id") id: Long,
        @Param("at") at: Instant,
    ): Int

    /**
     * A broker reply owns the conversation (ADMIN-8, ADMIN-14): HUMAN, read, active now, the takeover stamped once. A
     * conditional UPDATE instead of a save of the session the controller loaded, so a stamp or flag written meanwhile stays.
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.status = 'HUMAN', s.adminUnread = false, s.lastActivityAt = :now, " +
            "s.takenOverAt = COALESCE(s.takenOverAt, :at) WHERE s.id = :id",
    )
    fun markHumanReply(
        @Param("id") id: Long,
        @Param("at") at: Instant,
        @Param("now") now: Instant,
    ): Int

    /** The response stamps of a broker reply to a visitor who had written; each one is set once and never moved (spec 2.3). */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.firstHumanResponseAt = COALESCE(s.firstHumanResponseAt, :at), " +
            "s.takenOverAt = COALESCE(s.takenOverAt, :at), s.firstResponseAt = COALESCE(s.firstResponseAt, :at), " +
            "s.firstResponseBy = COALESCE(s.firstResponseBy, 'HUMAN') WHERE s.id = :id",
    )
    fun stampHumanReply(
        @Param("id") id: Long,
        @Param("at") at: Instant,
    ): Int

    /**
     * The widget heartbeat writes only its own columns. A whole-row save of the session loaded at the start of the request
     * could undo a takeover, a reply, a lock or a flag written in the meantime (P2 review I-2).
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiChatSession s SET s.lastSeenAt = :lastSeenAt, s.currentPage = :currentPage, s.pageTrail = :pageTrail, " +
            "s.referrer = :referrer WHERE s.id = :id",
    )
    fun updatePresence(
        @Param("id") id: Long,
        @Param("lastSeenAt") lastSeenAt: Instant,
        @Param("currentPage") currentPage: String?,
        @Param("pageTrail") pageTrail: String?,
        @Param("referrer") referrer: String?,
    )

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
