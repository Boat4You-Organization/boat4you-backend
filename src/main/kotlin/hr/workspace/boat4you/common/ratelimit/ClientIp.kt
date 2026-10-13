package hr.workspace.boat4you.common.ratelimit

import jakarta.servlet.http.HttpServletRequest

/** Client address as PublicEndpointRateLimiter sees it (also stored with custom payments). */
object ClientIp {
    fun of(request: HttpServletRequest): String {
        // Respect X-Forwarded-For when behind Nginx / CDN. Pick the left-most
        // entry (original client). Falls back to socket address otherwise.
        // F1-022: blindly trusting XFF is unsafe under the current nginx
        // config; the real fix lives in the Faza 7 nginx batch.
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        val xri = request.getHeader("X-Real-IP")
        if (!xri.isNullOrBlank()) return xri.trim()
        return request.remoteAddr ?: "unknown"
    }
}
