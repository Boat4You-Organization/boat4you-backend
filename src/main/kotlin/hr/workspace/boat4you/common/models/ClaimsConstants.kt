package hr.workspace.boat4you.common.models

object ClaimsConstants {
    const val USER_ROLES = "userRoles"
    const val USER_ID = "userId"
    const val USER_EMAIL = "userEmail"

    /**
     * "access" or "refresh" (security audit 15.11.2026): an access token is the Bearer of API calls, a refresh token
     * only renews it at /auth/refreshToken. Before, either was taken for the other. Tokens issued before this claim:
     * an access token carries [USER_ROLES], a refresh token does not.
     */
    const val TOKEN_TYPE = "tokenType"
    const val TOKEN_TYPE_ACCESS = "access"
    const val TOKEN_TYPE_REFRESH = "refresh"
}
