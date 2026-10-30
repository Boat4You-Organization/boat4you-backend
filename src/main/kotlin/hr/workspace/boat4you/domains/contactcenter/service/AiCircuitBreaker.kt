package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEvent
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * The AI circuit breaker of the chat (design 2.6): during a surge the chat stops calling the model, for all sites (global
 * scope) or for one site, and the visitor is told to leave an email or a phone. Per scope and [Metric] it counts the
 * events of the last minute; a count above its `cc.breaker.*` threshold opens the scope. An automatic open is checked
 * again by [tick] once `cc.breaker.cooldown.minutes` have passed: below every threshold it closes, otherwise it waits
 * another cooldown. Mario can open or close a scope by hand; a manual open never closes by itself and survives a restart
 * through [restoreManualState]. Every open and close is a row of the security log; an automatic open mails the team, at
 * most [MAILS_PER_HOUR] an hour. One JVM (cusma2) counts all chat traffic, so in-memory counts are the whole picture.
 */
@Component
class AiCircuitBreaker(
    private val settings: CcAiSettings,
    private val events: CcSecurityEventRepository,
    private val emailService: EmailService,
    @Value("\${chat.notify-email:info@boat4you.com}") private val notifyEmail: String,
) {
    enum class Metric { SESSIONS, MESSAGES, LOCKS }

    data class ScopeState(
        val siteId: String?,
        val open: Boolean,
        val manual: Boolean,
        val openedAt: Instant?,
        val reason: String?,
        val sessionsPerMin: Int,
        val messagesPerMin: Int,
        val locksPerMin: Int,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    internal var clock: () -> Instant = Instant::now
    internal var mailExecutor: (Runnable) -> Unit = { Thread(it, "cc-breaker-mail").start() }

    /** Per-second counts of the last 60 seconds: slot `second % 60`, reset when the second it holds is older. */
    private class Window {
        val counts = IntArray(60)
        val seconds = LongArray(60) { Long.MIN_VALUE }

        fun add(second: Long) {
            val slot = Math.floorMod(second, 60L).toInt()
            if (seconds[slot] != second) {
                seconds[slot] = second
                counts[slot] = 0
            }
            counts[slot]++
        }

        fun count(nowSecond: Long): Int {
            var sum = 0
            for (i in 0 until 60) if (seconds[i] > nowSecond - 60 && seconds[i] <= nowSecond) sum += counts[i]
            return sum
        }
    }

    /** One scope: [siteId] null = global (every site). */
    private class Scope(
        val siteId: String?,
    ) {
        val windows = Metric.entries.associateWith { Window() }
        var open = false
        var manual = false
        var openedAt: Instant? = null
        var reason: String? = null

        fun count(
            metric: Metric,
            nowSecond: Long,
        ): Int = windows.getValue(metric).count(nowSecond)

        /** The first metric above its threshold, as "MESSAGES 51/min > 50"; null when every count is within. */
        fun overThreshold(
            limits: Map<Metric, Int>,
            nowSecond: Long,
        ): String? =
            Metric.entries.firstNotNullOfOrNull { m ->
                val n = count(m, nowSecond)
                val limit = limits.getValue(m)
                if (n > limit) "$m $n/min > $limit" else null
            }
    }

    /** What a state change leaves to do outside the lock: the security log row and, for an automatic open, the mail. */
    private data class Transition(
        val type: String,
        val siteId: String?,
        val detail: String,
        val reason: String?,
        val at: Instant,
        val mail: Boolean,
        val cooldownMinutes: Int,
    )

    private val global = Scope(null)
    private val sites = HashMap<String, Scope>()
    private val mailsSent = ArrayDeque<Instant>()

    /** When each address last had a lock counted (epoch second); one lock per address a minute counts, oldest evicted first. */
    private val lockCountedAt =
        object : LinkedHashMap<String, Long>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_LOCK_ADDRESSES
        }

    /** Sessions that got the "AI paused" notice in the current open period; bounded, oldest evicted first. */
    private val notices: MutableSet<Long> =
        Collections.newSetFromMap(
            object : LinkedHashMap<Long, Boolean>(1024, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>): Boolean = size > MAX_NOTICES
            },
        )

    fun recordNewSession(siteId: String) = record(siteId, Metric.SESSIONS)

    fun recordVisitorMessage(siteId: String) = record(siteId, Metric.MESSAGES)

    /**
     * A lock counts towards the breaker once per [ip] a minute: one address that keeps getting locked (a probe loop, a
     * hostile name on every new session) must not pause the AI for every visitor; locks from several addresses in a minute
     * are the surge the breaker is for. Without an address every lock counts.
     */
    fun recordLock(
        siteId: String,
        ip: String? = null,
    ) {
        if (ip != null && !firstLockThisMinute(ip)) return
        record(siteId, Metric.LOCKS)
    }

    /** After a restart a manual open is back before the first request is served. */
    @PostConstruct
    fun restoreOnStart() = restoreManualState()

    /** True while the global scope or the scope of [siteId] is open: the chat must not call the model for that site. */
    fun isOpen(siteId: String): Boolean = synchronized(this) { global.open || sites[siteId]?.open == true }

    /** True the first time [sessionId] asks in the current open period: that session gets the "AI paused" notice once. */
    fun claimNotice(sessionId: Long): Boolean = synchronized(this) { notices.add(sessionId) }

    /** Closes every automatic open whose cooldown has passed and whose counts are back within the thresholds. */
    fun tick() {
        val v = settings.current()
        val done =
            synchronized(this) {
                val now = clock()
                val second = now.epochSecond
                val cooldown = Duration.ofMinutes(v.breakerCooldownMinutes.toLong())
                (listOf(global) + sites.values).mapNotNull { scope ->
                    val openedAt = scope.openedAt
                    when {
                        !scope.open || scope.manual || openedAt == null || now < openedAt.plus(cooldown) -> null
                        scope.overThreshold(limits(scope, v), second) == null -> {
                            closeScope(scope)
                            Transition(
                                CcSecurityEvent.TYPE_BREAKER_CLOSE,
                                scope.siteId,
                                "auto: below thresholds after ${v.breakerCooldownMinutes} min",
                                null,
                                now,
                                false,
                                v.breakerCooldownMinutes,
                            )
                        }
                        else -> {
                            scope.openedAt = now
                            log.info("AI breaker {} still above thresholds, open for another {} min", label(scope.siteId), v.breakerCooldownMinutes)
                            null
                        }
                    }
                }
            }
        done.forEach(::apply)
    }

    /** Opens [siteId]'s scope (null = global) by hand; it stays open until [close]. */
    fun open(
        siteId: String?,
        by: String,
    ) {
        val v = settings.current()
        val done =
            synchronized(this) {
                val scope = scopeOf(siteId) ?: return
                if (scope.open && scope.manual) return
                val now = clock()
                scope.open = true
                scope.manual = true
                scope.openedAt = now
                scope.reason = "manual by $by"
                Transition(CcSecurityEvent.TYPE_BREAKER_OPEN, siteId, "manual by $by", null, now, false, v.breakerCooldownMinutes)
            }
        apply(done)
    }

    /** Closes [siteId]'s scope (null = global) by hand, an automatic open as well. */
    fun close(
        siteId: String?,
        by: String,
    ) {
        val v = settings.current()
        val done =
            synchronized(this) {
                val scope = scopeOf(siteId) ?: return
                if (!scope.open) return
                closeScope(scope)
                Transition(CcSecurityEvent.TYPE_BREAKER_CLOSE, siteId, "manual by $by", null, clock(), false, v.breakerCooldownMinutes)
            }
        apply(done)
    }

    /** The global scope first (always), then every site scope that is open or counted something in the last minute. */
    fun states(): List<ScopeState> =
        synchronized(this) {
            val second = clock().epochSecond
            val active = sites.values.filter { s -> s.open || Metric.entries.any { s.count(it, second) > 0 } }.sortedBy { it.siteId }
            (listOf(global) + active).map { s ->
                ScopeState(
                    s.siteId,
                    s.open,
                    s.manual,
                    s.openedAt,
                    s.reason,
                    s.count(Metric.SESSIONS, second),
                    s.count(Metric.MESSAGES, second),
                    s.count(Metric.LOCKS, second),
                )
            }
        }

    /** After a restart: every scope whose latest breaker event is a manual open is open again (no new event, no mail). */
    fun restoreManualState() {
        val latest =
            runCatching { events.latestBreakerEventPerScope() }
                .getOrElse {
                    log.warn("AI breaker: manual state not restored, security log not readable: {}", it.message)
                    return
                }
        synchronized(this) {
            for (e in latest) {
                if (e.type != CcSecurityEvent.TYPE_BREAKER_OPEN || e.detail?.startsWith("manual") != true) continue
                val scope = scopeOf(e.siteId) ?: continue
                scope.open = true
                scope.manual = true
                scope.openedAt = e.createdAt
                scope.reason = e.detail
                log.warn("AI breaker {} restored as manually open ({})", label(e.siteId), e.detail)
            }
        }
    }

    private fun record(
        siteId: String,
        metric: Metric,
    ) {
        val v = settings.current()
        val done =
            synchronized(this) {
                val now = clock()
                val second = now.epochSecond
                val site = if (SiteIds.isKnown(siteId)) sites.getOrPut(siteId) { Scope(siteId) } else null
                site?.windows?.getValue(metric)?.add(second)
                global.windows.getValue(metric).add(second)
                listOfNotNull(site?.let { autoOpen(it, v, now) }, autoOpen(global, v, now))
            }
        done.forEach(::apply)
    }

    /** Opens a closed [scope] when one of its counts is above its threshold. Caller holds the lock. */
    private fun autoOpen(
        scope: Scope,
        v: CcAiSettings.Values,
        now: Instant,
    ): Transition? {
        if (scope.open) return null
        val reason = scope.overThreshold(limits(scope, v), now.epochSecond) ?: return null
        scope.open = true
        scope.manual = false
        scope.openedAt = now
        scope.reason = reason
        log.warn("AI breaker {} opened: {}", label(scope.siteId), reason)
        return Transition(CcSecurityEvent.TYPE_BREAKER_OPEN, scope.siteId, "auto: $reason", reason, now, allowMail(now), v.breakerCooldownMinutes)
    }

    /** Caller holds the lock. */
    private fun closeScope(scope: Scope) {
        scope.open = false
        scope.manual = false
        scope.openedAt = null
        scope.reason = null
        // A new open period starts with no notices given, but only once no scope is open any more: a session on a scope
        // that is still open must not get the "AI paused" line twice.
        if (!global.open && sites.values.none { it.open }) notices.clear()
        log.warn("AI breaker {} closed", label(scope.siteId))
    }

    private fun firstLockThisMinute(ip: String): Boolean =
        synchronized(this) {
            val second = clock().epochSecond
            val last = lockCountedAt[ip]
            if (last != null && last > second - 60) {
                false
            } else {
                lockCountedAt.remove(ip)
                lockCountedAt[ip] = second
                true
            }
        }

    /** Caller holds the lock. */
    private fun allowMail(now: Instant): Boolean {
        val cutoff = now.minus(Duration.ofHours(1))
        while (mailsSent.isNotEmpty() && mailsSent.first().isBefore(cutoff)) mailsSent.removeFirst()
        if (mailsSent.size >= MAILS_PER_HOUR) return false
        mailsSent.addLast(now)
        return true
    }

    /** Caller holds the lock. Null for a site id that is not one of the seven. */
    private fun scopeOf(siteId: String?): Scope? =
        when {
            siteId == null -> global
            SiteIds.isKnown(siteId) -> sites.getOrPut(siteId) { Scope(siteId) }
            else -> null
        }

    private fun limits(
        scope: Scope,
        v: CcAiSettings.Values,
    ): Map<Metric, Int> =
        if (scope.siteId == null) {
            mapOf(Metric.SESSIONS to v.globalSessionsPerMin, Metric.MESSAGES to v.globalMessagesPerMin, Metric.LOCKS to v.globalLocksPerMin)
        } else {
            mapOf(Metric.SESSIONS to v.siteSessionsPerMin, Metric.MESSAGES to v.siteMessagesPerMin, Metric.LOCKS to v.siteLocksPerMin)
        }

    /** Writes the security log row and, for an automatic open, sends the mail; outside the lock. */
    private fun apply(t: Transition) {
        events.save(
            CcSecurityEvent().apply {
                type = t.type
                siteId = t.siteId
                detail = t.detail
                createdAt = t.at
            },
        )
        if (!t.mail) return
        val scope = label(t.siteId)
        mailExecutor {
            runCatching {
                emailService.sendEmail(
                    recipients = listOf(notifyEmail),
                    subject = "Chat: AI automatski isključen ($scope) — ${t.reason}",
                    templateName = "email/ccBreakerNotification",
                    variables =
                        mapOf(
                            "headline" to "AI je automatski isključen zbog navale u chatu",
                            "scope" to scope,
                            "reason" to t.reason,
                            "openedAt" to MAIL_TIME.format(t.at),
                            "cooldownMinutes" to t.cooldownMinutes,
                            "adminUrl" to ADMIN_URL,
                            "currentYear" to Year.now(ZoneOffset.UTC).value,
                        ),
                )
            }.onFailure { log.warn("AI breaker mail not sent: {}", it.message) }
        }
    }

    private fun label(siteId: String?): String = siteId ?: "global"

    companion object {
        const val MAX_NOTICES = 10_000
        const val MAILS_PER_HOUR = 5
        const val MAX_LOCK_ADDRESSES = 10_000
        private const val ADMIN_URL = "https://admin.boat4you.com/chat"
        private val MAIL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
