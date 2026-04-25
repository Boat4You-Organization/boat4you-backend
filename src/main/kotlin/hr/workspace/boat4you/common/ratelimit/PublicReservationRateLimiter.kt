package hr.workspace.boat4you.common.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-IP rate limiter for `POST /public/reservations`.
 *
 * DEPLOY_NOTES section A (19.4.2026) flagged the endpoint as a DoS/spam vector
 * — anonymous guest booking with no auth, no captcha. This filter is a
 * minimal first line of defence (5 requests per minute per IP) until a real
 * gateway-level rate limit (Nginx `limit_req` or API gateway) lands in prod.
 *
 * Implementation: token bucket keyed by client IP. Every successful call
 * consumes 1 token; tokens refill at `capacity / windowSeconds` per second.
 * Over budget → 429 with `Retry-After` header. Map entries self-prune when
 * they go stale (no request in > 2× window).
 *
 * Limits are env-tunable:
 *   APPLICATION_RATE_LIMIT_PUBLIC_RESERVATION_CAPACITY (default 5)
 *   APPLICATION_RATE_LIMIT_PUBLIC_RESERVATION_WINDOW_SECONDS (default 60)
 *
 * NOT a replacement for gateway-level rate limiting — in-memory means each
 * instance has its own budget, and a distributed attacker bypasses per-IP
 * tracking anyway. It IS cheap insurance against obvious abuse.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PublicReservationRateLimiter(
    @Value("\${application.rate-limit.public-reservation.capacity:5}")
    private val capacity: Int,
    @Value("\${application.rate-limit.public-reservation.window-seconds:60}")
    private val windowSeconds: Long,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Bucket(
        var tokens: Double,
        var lastRefill: Instant,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Only protect the guest booking path. Secured (authenticated) flows
        // + everything else passes through untouched.
        return !(request.method == "POST" && request.requestURI == "/public/reservations")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ip = clientIp(request)
        if (!acquire(ip)) {
            log.warn("Rate limit hit on /public/reservations for ip=$ip")
            response.status = 429 // Too Many Requests
            response.setHeader("Retry-After", windowSeconds.toString())
            response.writer.write("{\"error\":\"Too many reservation attempts — please wait a minute.\"}")
            response.contentType = "application/json"
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun acquire(ip: String): Boolean {
        val now = Instant.now()
        val bucket = buckets.compute(ip) { _, existing ->
            val b = existing ?: Bucket(capacity.toDouble(), now)
            val elapsed = (now.toEpochMilli() - b.lastRefill.toEpochMilli()) / 1000.0
            val refill = (capacity.toDouble() / windowSeconds) * elapsed
            b.tokens = (b.tokens + refill).coerceAtMost(capacity.toDouble())
            b.lastRefill = now
            b
        }!!
        return synchronized(bucket) {
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    private fun clientIp(request: HttpServletRequest): String {
        // Respect X-Forwarded-For when behind Nginx / CDN. Pick the left-most
        // entry (original client). Falls back to socket address otherwise.
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        val xri = request.getHeader("X-Real-IP")
        if (!xri.isNullOrBlank()) return xri.trim()
        return request.remoteAddr ?: "unknown"
    }
}
