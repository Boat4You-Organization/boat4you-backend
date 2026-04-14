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
}
