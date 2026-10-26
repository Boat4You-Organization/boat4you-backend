package hr.workspace.boat4you.domains.chat.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface AiChatSessionRepository : JpaRepository<AiChatSession, Long> {
    fun findByTokenAndDeletedAtIsNull(token: String): AiChatSession?

    fun findAllByDeletedAtIsNullAndStatusInOrderByLastActivityAtDesc(statuses: Collection<String>, pageable: Pageable): List<AiChatSession>

    fun findAllByDeletedAtIsNullOrderByLastActivityAtDesc(pageable: Pageable): List<AiChatSession>

    fun findAllByDeletedAtIsNullAndFlaggedTrueOrderByLastActivityAtDesc(pageable: Pageable): List<AiChatSession>

    /** Sessions from one IP the guard locked since [after] - repeat offenders start locked (review B1.6). */
    fun countByIpAndFlaggedTrueAndLockedAtAfter(ip: String, after: Instant): Long

    fun countByStatusAndAdminUnreadTrue(status: String): Long
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
}
