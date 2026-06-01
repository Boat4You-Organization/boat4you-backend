package hr.workspace.boat4you.security.jpa

import org.springframework.stereotype.Service

@Service
class TokenService(
    private val tokenRepository: TokenRepository,
) {
    fun revokeAllUserTokens(userId: Long) {
        val validUserTokens = tokenRepository.findAllValidTokenByUserId(userId)
        if (validUserTokens.isNotEmpty()) {
            validUserTokens.forEach { dbToken ->
                dbToken.isExpired = true
                dbToken.isRevoked = true
            }
            tokenRepository.saveAll(validUserTokens)
        }
    }

    /** Hard-delete all token rows for a user (used when deleting the user) —
     *  revokeAllUserTokens only flags them, leaving rows that block the
     *  user delete via the tokens.user_id FK. */
    @org.springframework.transaction.annotation.Transactional
    fun deleteAllUserTokens(userId: Long) {
        tokenRepository.deleteByUserId(userId)
    }
}
