package hr.workspace.boat4you.security.services

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class ClientIpResolver {
    fun resolve(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) {
            return xff.split(",").first().trim().take(64)
        }
        return (request.remoteAddr ?: "").take(64)
    }
}
