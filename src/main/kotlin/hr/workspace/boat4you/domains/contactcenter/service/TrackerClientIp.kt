package hr.workspace.boat4you.domains.contactcenter.service

import jakarta.servlet.http.HttpServletRequest
import org.apache.commons.validator.routines.InetAddressValidator

/**
 * The client address of a tracker request: the `X-Real-IP` that nginx sets (BACKEND-22, BACKEND-24), and only when it is an IP
 * literal, so a forged name never reaches a DNS lookup. `X-Forwarded-For` is never read: its left-most entry comes from the client.
 */
object TrackerClientIp {
    fun of(request: HttpServletRequest): String =
        request.getHeader("X-Real-IP")?.trim()?.takeIf { isIpLiteral(it) }
            ?: request.remoteAddr
            ?: "unknown"

    /** True for an IPv4 or IPv6 address literal; a host name or an empty value is not one. */
    fun isIpLiteral(s: String?): Boolean = s != null && InetAddressValidator.getInstance().isValid(s)
}
