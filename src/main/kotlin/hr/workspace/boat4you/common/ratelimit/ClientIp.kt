package hr.workspace.boat4you.common.ratelimit

import hr.workspace.boat4you.domains.contactcenter.service.TrackerClientIp
import jakarta.servlet.http.HttpServletRequest

/**
 * The address of whoever a request is really from (F1-022, 12.11.2026); also stored with custom payments, sessions and the
 * GDPR audit trail.
 *
 * nginx overwrites `X-Real-IP` with the address that connected to it, so that header cannot be forged. `X-Forwarded-For` can:
 * nginx only appends to what the caller sent. The forwarded address is therefore believed only when the caller is one of our
 * own web servers ([DEFAULT_TRUSTED_FORWARDERS]), whose server-side calls carry the visitor's address there; without it every
 * visitor of a site would share that server's budget. Anyone else is keyed on the address they connected from.
 */
object ClientIp {
    /** cusma1 (www.boat4you.com) and cusma5 (the six sister sites), public and private-LAN addresses. */
    const val DEFAULT_TRUSTED_FORWARDERS = "91.98.209.180,91.98.209.185,192.168.55.1,192.168.55.5"

    private val defaultTrusted = parseForwarders(DEFAULT_TRUSTED_FORWARDERS)

    fun parseForwarders(csv: String): Set<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun of(
        request: HttpServletRequest,
        trustedForwarders: Set<String> = defaultTrusted,
    ): String {
        val peer = TrackerClientIp.of(request)
        if (peer !in trustedForwarders) return peer
        // Our web server sends exactly the visitor address; a name, an empty value or one of our own addresses is not one.
        val forwarded = request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
        return forwarded?.takeIf { TrackerClientIp.isIpLiteral(it) && it !in trustedForwarders } ?: peer
    }
}
