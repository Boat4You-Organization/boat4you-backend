package hr.workspace.boat4you.security.dto

import java.time.Instant

data class SessionDto(
    val sessionGroup: String,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val current: Boolean,
)
