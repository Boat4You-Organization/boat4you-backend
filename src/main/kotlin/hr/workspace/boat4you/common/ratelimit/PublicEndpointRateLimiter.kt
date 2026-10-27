package hr.workspace.boat4you.common.ratelimit

import hr.workspace.boat4you.domains.contactcenter.service.TrackerClientIp
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-(endpoint, IP) rate limiter for anonymous-reachable
 * POST endpoints that would otherwise be abuse vectors.
 *
 * Currently protects:
 *  - `POST /public/reservations` — anonymous guest booking
 *    (DEPLOY_NOTES section A, 19.4.2026: DoS/spam vector).
 *  - `POST /public/inquiries` — anonymous broker-notification email
 *    trigger (F1-069). Each accepted inquiry dispatches a real email
 *    to the broker mailbox, so an unbounded loop would exhaust SMTP
 *    quota and bury legitimate alerts.
 *  - `POST /public/users/set-password-for-reservation` — anonymous
 *    guest password setup gated only by reservationId + email match
 *    (F1-057). Without a rate limit, an attacker can enumerate
 *    reservation ids and try email guesses unbounded.
 *  - `POST /auth/login` — credential stuffing / brute-force vector.
 *    Per-account lockout (5 attempts / 15 min) exists in UserAuthService
 *    but does not stop cross-account spraying from a single IP.
 *  - `POST /auth/register` — unbounded registration creates garbage
 *    accounts and triggers verification emails (SMTP quota exhaustion).
 *  - `POST /auth/requestPasswordReset` — each call sends a real email;
 *    unbounded calls flood the victim's inbox and exhaust SMTP quota.
 *  - `POST /auth/register/verifyEmail` — 6-digit code brute-force
 *    vector. Per-user attempt counter exists but per-IP throttle adds
 *    defense in depth.
 *  - `POST /public/reviews/request/{token}` — anonymous review submit
 *    behind the e-mail magic link (25.9.2026). The 256-bit token cannot be
 *    guessed; the limit only stops a script from hammering one link.
 *  - `POST /public/chat/sessions`, `.../{token}/messages`, `.../{token}/name`
 *    — the AI chat (11.10.2026, review B5): each message can cost several
 *    Anthropic calls, each new session can mail the broker. The 30 s presence
 *    heartbeat stays unlimited here (several open tabs), nginx covers it.
 *  - `POST /public/cc/v1/visit|beat|handoff` (Contact Center tracker,
 *    26.10.2026): 60 / 600 / 10 per 10 min, keyed on X-Real-IP; own nginx
 *    zone `cc_tracker_zone`.
 *  - Chat rules key on X-Real-IP since 26.10.2026 (BACKEND-22); inquiries,
 *    reviews, reservations and auth keep the left-most X-Forwarded-For, which
 *    boat4you-web server actions fill with the visitor IP.
 *
 * Implementation: token bucket keyed by (rule path, client IP). Every
 * accepted call consumes 1 token; tokens refill at
 * `capacity / windowSeconds` per second. Over budget → 429 with a
 * `Retry-After` header. Idle buckets are removed once a minute
 * ([pruneIdleBuckets], F1-023): a bucket idle for its whole window is full
 * again, so removing it never changes a decision.
 *
 * Per-endpoint limits are env-tunable. Defaults are deliberately tight
 * (5 req/min/IP for everything) so the obvious-abuse case is blocked
 * without thinking; legitimate users hit at most one of these per
 * session.
 *
 * NOT a replacement for gateway-level rate limiting — in-memory means
 * each VM has its own budget, and a distributed attacker across many
 * IPs bypasses per-IP tracking anyway. Nginx `limit_req` is the real
 * defense; this is cheap insurance until that lands (F1-003 + F1-022
 * are tracked as DEFERRED-Faza7 nginx batch). Trusting X-Forwarded-For
 * blindly is also a known issue (F1-022) — same nginx batch fixes it
 * via `proxy_add_x_forwarded_for`. The chat and tracker rules therefore key
 * on X-Real-IP (BACKEND-22: the browser calls them straight on api, where
 * the left-most XFF entry is client-forgeable); every other rule still
 * reads the left-most XFF.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PublicEndpointRateLimiter(
    @Value("\${application.rate-limit.public-reservation.capacity:5}")
    private val reservationCapacity: Int,
    @Value("\${application.rate-limit.public-reservation.window-seconds:60}")
    private val reservationWindowSeconds: Long,
    @Value("\${application.rate-limit.public-inquiry.capacity:5}")
    private val inquiryCapacity: Int,
    @Value("\${application.rate-limit.public-inquiry.window-seconds:60}")
    private val inquiryWindowSeconds: Long,
    @Value("\${application.rate-limit.public-set-password.capacity:5}")
    private val setPasswordCapacity: Int,
    @Value("\${application.rate-limit.public-set-password.window-seconds:60}")
    private val setPasswordWindowSeconds: Long,
    @Value("\${application.rate-limit.auth-login.capacity:10}")
    private val loginCapacity: Int,
    @Value("\${application.rate-limit.auth-login.window-seconds:60}")
    private val loginWindowSeconds: Long,
    @Value("\${application.rate-limit.auth-register.capacity:3}")
    private val registerCapacity: Int,
    @Value("\${application.rate-limit.auth-register.window-seconds:60}")
    private val registerWindowSeconds: Long,
    @Value("\${application.rate-limit.auth-password-reset.capacity:3}")
    private val passwordResetCapacity: Int,
    @Value("\${application.rate-limit.auth-password-reset.window-seconds:60}")
    private val passwordResetWindowSeconds: Long,
    @Value("\${application.rate-limit.auth-verify-email.capacity:5}")
    private val verifyEmailCapacity: Int,
    @Value("\${application.rate-limit.auth-verify-email.window-seconds:60}")
    private val verifyEmailWindowSeconds: Long,
    @Value("\${application.rate-limit.public-review.capacity:10}")
    private val reviewCapacity: Int,
    @Value("\${application.rate-limit.public-review.window-seconds:60}")
    private val reviewWindowSeconds: Long,
    @Value("\${application.rate-limit.chat-session.capacity:5}")
    private val chatSessionCapacity: Int,
    @Value("\${application.rate-limit.chat-session.window-seconds:600}")
    private val chatSessionWindowSeconds: Long,
    @Value("\${application.rate-limit.chat-message.capacity:40}")
    private val chatMessageCapacity: Int,
    @Value("\${application.rate-limit.chat-message.window-seconds:600}")
    private val chatMessageWindowSeconds: Long,
    @Value("\${application.rate-limit.chat-name.capacity:10}")
    private val chatNameCapacity: Int,
    @Value("\${application.rate-limit.chat-name.window-seconds:600}")
    private val chatNameWindowSeconds: Long,
    @Value("\${application.rate-limit.cc-visit.capacity:60}")
    private val ccVisitCapacity: Int,
    @Value("\${application.rate-limit.cc-visit.window-seconds:600}")
    private val ccVisitWindowSeconds: Long,
    @Value("\${application.rate-limit.cc-beat.capacity:600}")
    private val ccBeatCapacity: Int,
    @Value("\${application.rate-limit.cc-beat.window-seconds:600}")
    private val ccBeatWindowSeconds: Long,
    @Value("\${application.rate-limit.cc-handoff.capacity:10}")
    private val ccHandoffCapacity: Int,
    @Value("\${application.rate-limit.cc-handoff.window-seconds:600}")
    private val ccHandoffWindowSeconds: Long,
) : OncePerRequestFilter() {
    internal var clock: Clock = Clock.systemUTC()

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Rule(
        val method: String,
        val path: String,
        val prefix: Boolean = false,
        /** Matches the whole request URI instead of [path] (a token in the middle of the path). */
        val pattern: Regex? = null,
        val capacity: Int,
        val windowSeconds: Long,
        val label: String,
        /** Key on X-Real-IP (browser calls straight to api; XFF is client-forgeable there — BACKEND-22). */
        val realIp: Boolean = false,
    )

    private data class Bucket(
        var tokens: Double,
        var lastRefill: Instant,
    )

    private val rules by lazy {
        listOf(
            Rule("POST", "/public/reservations", capacity = reservationCapacity, windowSeconds = reservationWindowSeconds, label = "reservations"),
            Rule("POST", "/public/inquiries", capacity = inquiryCapacity, windowSeconds = inquiryWindowSeconds, label = "inquiries"),
            Rule("POST", "/public/users/set-password-for-reservation", capacity = setPasswordCapacity, windowSeconds = setPasswordWindowSeconds, label = "set-password"),
            Rule("POST", "/auth/login", capacity = loginCapacity, windowSeconds = loginWindowSeconds, label = "login"),
            Rule("POST", "/auth/register/verifyEmail", capacity = verifyEmailCapacity, windowSeconds = verifyEmailWindowSeconds, label = "verify-email"),
            Rule("POST", "/auth/register/resendVerificationCode/", prefix = true, capacity = registerCapacity, windowSeconds = registerWindowSeconds, label = "resend-verification"),
            Rule("POST", "/auth/register", capacity = registerCapacity, windowSeconds = registerWindowSeconds, label = "register"),
            Rule("POST", "/auth/requestPasswordReset", capacity = passwordResetCapacity, windowSeconds = passwordResetWindowSeconds, label = "password-reset"),
            Rule("POST", "/public/reviews/request/", prefix = true, capacity = reviewCapacity, windowSeconds = reviewWindowSeconds, label = "review-submit"),
            Rule("POST", "/public/chat/sessions", capacity = chatSessionCapacity, windowSeconds = chatSessionWindowSeconds, label = "chat-session", realIp = true),
            Rule(
                "POST",
                "/public/chat/sessions/{token}/messages",
                pattern = Regex("^/public/chat/sessions/[^/]+/messages$"),
                capacity = chatMessageCapacity,
                windowSeconds = chatMessageWindowSeconds,
                label = "chat-message",
                realIp = true,
            ),
            Rule(
                "POST",
                "/public/chat/sessions/{token}/name",
                pattern = Regex("^/public/chat/sessions/[^/]+/name$"),
                capacity = chatNameCapacity,
                windowSeconds = chatNameWindowSeconds,
                label = "chat-name",
                realIp = true,
            ),
            Rule("POST", "/public/cc/v1/visit", capacity = ccVisitCapacity, windowSeconds = ccVisitWindowSeconds, label = "cc-visit", realIp = true),
            Rule("POST", "/public/cc/v1/beat", capacity = ccBeatCapacity, windowSeconds = ccBeatWindowSeconds, label = "cc-beat", realIp = true),
            Rule("POST", "/public/cc/v1/handoff", capacity = ccHandoffCapacity, windowSeconds = ccHandoffWindowSeconds, label = "cc-handoff", realIp = true),
        )
    }

    private val windowByLabel by lazy { rules.associate { it.label to it.windowSeconds } }

    // Key = "<label>:<ip>" so the same IP gets independent budgets across
    // protected endpoints. A user who maxes out reservations should not
    // also be locked out of inquiry submission.
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return matchingRule(request) == null
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rule = matchingRule(request)
            ?: run {
                // Defensive: shouldNotFilter is the gate, but if Spring ever
                // dispatches us here without a match (e.g. servlet forward),
                // just pass through.
                filterChain.doFilter(request, response)
                return
            }
        val ip = clientIp(request, rule)
        if (!acquire(rule, ip)) {
            log.warn("Rate limit hit on {} {} for ip={}", rule.method, rule.path, ip)
            response.status = 429
            response.setHeader("Retry-After", rule.windowSeconds.toString())
            response.contentType = "application/json"
            response.writer.write(
                """{"error":"Too many requests to ${rule.path} — please wait."}""",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun matchingRule(request: HttpServletRequest): Rule? =
        rules.firstOrNull {
            it.method == request.method &&
                when {
                    it.pattern != null -> it.pattern.matches(request.requestURI)
                    it.prefix -> request.requestURI.startsWith(it.path)
                    else -> it.path == request.requestURI
                }
        }

    private fun acquire(rule: Rule, ip: String): Boolean {
        val key = "${rule.label}:$ip"
        val now = Instant.now(clock)
        val bucket = buckets.compute(key) { _, existing ->
            val b = existing ?: Bucket(rule.capacity.toDouble(), now)
            val elapsed = (now.toEpochMilli() - b.lastRefill.toEpochMilli()) / 1000.0
            val refill = (rule.capacity.toDouble() / rule.windowSeconds) * elapsed
            b.tokens = (b.tokens + refill).coerceAtMost(rule.capacity.toDouble())
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

    private fun clientIp(
        request: HttpServletRequest,
        rule: Rule,
    ): String {
        if (rule.realIp) return TrackerClientIp.of(request)
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

    /**
     * F1-023: nothing ever removed a bucket, and the tracker adds two IP-keyed buckets per visitor. A bucket idle for its whole window has
     * refilled to capacity, so dropping it changes no decision; `acquire` sets `lastRefill = now` inside `compute`, so a bucket in use is
     * never idle. The label never contains `:`, so an IPv6 key still splits correctly. Returns the number of buckets removed.
     */
    @Scheduled(fixedDelay = 60_000)
    fun pruneIdleBuckets(): Int {
        val now = Instant.now(clock)
        var removed = 0
        for (key in buckets.keys) {
            val window = windowByLabel.getValue(key.substringBefore(':'))
            buckets.computeIfPresent(key) { _, b ->
                if (Duration.between(b.lastRefill, now).seconds >= window) {
                    removed++
                    null
                } else {
                    b
                }
            }
        }
        return removed
    }
}
