package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.security.dto.SessionDto
import hr.workspace.boat4you.security.jpa.TokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SessionService(
    private val tokenRepository: TokenRepository,
) {
    @Transactional(readOnly = true)
    fun listSessions(
        userId: Long,
        currentTokenValue: String?,
    ): List<SessionDto> {
        val currentGroup = currentTokenValue?.let { tokenRepository.findByValue(it)?.sessionGroup }
        return tokenRepository
            .findActiveSessionTokens(userId)
            .groupBy { it.sessionGroup!! }
            .map { (group, tokens) ->
                val rep = tokens.maxByOrNull { it.lastUsedAt ?: it.created }!!
                SessionDto(
                    sessionGroup = group,
                    userAgent = rep.userAgent,
                    ipAddress = rep.ipAddress,
                    createdAt = rep.created,
                    lastUsedAt = rep.lastUsedAt,
                    current = group == currentGroup,
                )
            }.sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    @Transactional
    fun revokeSession(
        userId: Long,
        sessionGroup: String,
    ) {
        tokenRepository
            .findActiveSessionTokens(userId)
            .filter { it.sessionGroup == sessionGroup }
            .forEach {
                it.isRevoked = true
                tokenRepository.save(it)
            }
    }

    @Transactional
    fun revokeOtherSessions(
        userId: Long,
        currentTokenValue: String?,
    ) {
        val currentGroup = currentTokenValue?.let { tokenRepository.findByValue(it)?.sessionGroup }
        tokenRepository
            .findActiveSessionTokens(userId)
            .filter { it.sessionGroup != currentGroup }
            .forEach {
                it.isRevoked = true
                tokenRepository.save(it)
            }
    }
}
