package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.common.ratelimit.ClientIp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class ClientIpResolver {
    /** The address stored with a login session: never a forged `X-Forwarded-For` ([ClientIp], F1-022). */
    fun resolve(request: HttpServletRequest): String = ClientIp.of(request).take(64)
}
