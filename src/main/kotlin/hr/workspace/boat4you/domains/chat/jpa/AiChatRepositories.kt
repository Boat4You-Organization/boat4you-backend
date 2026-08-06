package hr.workspace.boat4you.domains.chat.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AiChatSessionRepository : JpaRepository<AiChatSession, Long> {
    fun findByToken(token: String): AiChatSession?

    fun findAllByStatusInOrderByLastActivityAtDesc(statuses: Collection<String>, pageable: Pageable): List<AiChatSession>

    fun findAllByOrderByLastActivityAtDesc(pageable: Pageable): List<AiChatSession>

    fun countByStatusAndAdminUnreadTrue(status: String): Long
}

interface AiChatMessageRepository : JpaRepository<AiChatMessage, Long> {
    fun findAllBySessionIdOrderByIdAsc(sessionId: Long): List<AiChatMessage>

    fun findAllBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId: Long, afterId: Long): List<AiChatMessage>

    fun countBySessionId(sessionId: Long): Long

    fun deleteAllBySessionId(sessionId: Long)
}
