package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A proactive message an admin queued for a visitor; it is persisted as a chat line only when it is delivered (Task 11). */
data class PendingProactive(
    val text: String,
    val adminUserId: Long,
    val queuedAt: Instant,
)

/**
 * One visit that is on a site now. Immutable facts are constructor properties; everything that changes while the visit goes on is a
 * `var` below. All mutable fields are written under `synchronized(this)`; [lastBeatAt] and [bot] are also read lock-free (by
 * [LiveVisitorRegistry.register], [LiveVisitorRegistry.evictExpired] and [LiveVisitorRegistry.liveSnapshot]), hence `@Volatile`.
 */
@Suppress("LongParameterList") // the immutable facts of one visit, built once by VisitService
class LiveEntry(
    val visitId: Long,
    val visitKey: String,
    val visitorId: Long,
    val visitorKey: String,
    val siteId: String,
    val startedAt: Instant,
    val returning: Boolean,
    val countryCode: String?,
    val ip: String?,
    val locale: String?,
    val deviceClass: String,
    val browser: String?,
    val os: String?,
    val channel: String,
    val fromSiteId: String?,
    val staff: Boolean,
    @Volatile var bot: Boolean,
    lastBeatAt: Instant,
) {
    @Volatile var lastBeatAt: Instant = lastBeatAt
    var visible: Boolean = true
    var currentPath: String? = null
    var currentTitle: String? = null
    var pageType: String? = null
    var boatSlug: String? = null
    var currentPageSince: Instant? = lastBeatAt
    var pageCount: Int = 0

    /** When `cc_visit.last_seen_at` was last written for this visit (the beat service writes it at most once per 15 s). */
    var seenPersistedAt: Instant = lastBeatAt

    /** Proactive messages waiting for this visitor's next beat, oldest first. Guarded by `synchronized(this)`. */
    val pending = ArrayDeque<PendingProactive>()

    /** The outcome of [notePageView]: a retried page view, a new one, or a new one that is the 31st within 60 s (not a person, [BotFilter.PV_RATE]). */
    enum class PvNote { DUPLICATE, NEW, NEW_OVER_RATE }

    /** The id hashes of the last 32 distinct page views (a ring), to recognise a beat the widget retried. */
    private val recent = IntArray(32)
    private var recentN = 0

    /** The times of the last [BotFilter.MAX_PAGE_VIEWS_PER_MINUTE] + 1 page views (a ring), to measure the rate. */
    private val stamps = LongArray(BotFilter.MAX_PAGE_VIEWS_PER_MINUTE + 1)
    private var stampN = 0L

    @Synchronized
    fun notePageView(
        idHash: Int,
        nowMs: Long,
    ): PvNote {
        for (i in 0 until minOf(recentN, recent.size)) if (recent[i] == idHash) return PvNote.DUPLICATE
        recent[recentN % recent.size] = idHash
        recentN = (recentN + 1) % (recent.size * 1_000)
        stamps[(stampN % stamps.size).toInt()] = nowMs
        stampN++
        pageCount++
        val oldestOfLast31 = stamps[(stampN % stamps.size).toInt()]
        return if (stampN > BotFilter.MAX_PAGE_VIEWS_PER_MINUTE && nowMs - oldestOfLast31 < 60_000) PvNote.NEW_OVER_RATE else PvNote.NEW
    }
}

/**
 * The in-memory list of the visits that are on the sites now (design 2.6): at most [MAX_ENTRIES], each dropped [EVICT_AFTER] after its
 * last beat (swept every 10 s). Bots and staff are kept, so a resumed visit is recognised, but are never in [liveSnapshot]. It also holds
 * the proactive messages waiting for a visitor's next beat: at most [MAX_PENDING_PER_VISITOR] each and [MAX_PENDING_TOTAL] in all.
 *
 * Lock order is registry, then entry; nothing takes the registry lock while holding an entry's.
 */
@Suppress("TooManyFunctions") // the registry's public API for the visit, beat, proactive and security-log services; each member is one operation
@Component
class LiveVisitorRegistry : LiveVisitorQuery {
    internal var clock: Clock = Clock.systemUTC()

    private val entries = ConcurrentHashMap<String, LiveEntry>()
    private val pendingTotal = AtomicInteger()
    private val rejectedCount = AtomicLong()
    private val botCounts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Adds the visit, or, if its key is already here, refreshes the entry that is (a resumed visit; the new entry may carry a bot reason
     * the old one did not have). False only when the registry is full of live people: then expired entries go first, then the oldest
     * bot or staff entry, else the visit is refused and counted in [rejected].
     */
    @Synchronized
    fun register(entry: LiveEntry): Boolean {
        val existing = entries[entry.visitKey]
        if (existing != null) {
            refresh(existing, entry)
            return true
        }
        makeRoom()
        val admitted = entries.size < MAX_ENTRIES
        if (admitted) entries[entry.visitKey] = entry else rejectedCount.incrementAndGet()
        return admitted
    }

    fun get(visitKey: String): LiveEntry? = entries[visitKey]

    /** The visitor's most recent visit here, whatever its flags: the one a proactive message goes to. */
    fun latestForVisitor(visitorKey: String): LiveEntry? = entries.values.filter { it.visitorKey == visitorKey }.maxByOrNull { it.lastBeatAt }

    fun remove(visitKey: String) {
        entries[visitKey]?.let { drop(it) }
    }

    /** Drops the visits silent for more than [EVICT_AFTER]. Returns how many. */
    @Scheduled(fixedDelay = 10_000)
    fun evictExpired(): Int {
        val cutoff = clock.instant().minus(EVICT_AFTER)
        var evicted = 0
        for (entry in entries.values) if (entry.lastBeatAt.isBefore(cutoff) && drop(entry)) evicted++
        return evicted
    }

    override fun liveSnapshot(): List<LiveVisitor> {
        val cutoff = clock.instant().minus(EVICT_AFTER)
        return entries.values
            .filter { !it.bot && !it.staff && !it.lastBeatAt.isBefore(cutoff) }
            .map { synchronized(it) { visitorOf(it) } }
            .sortedByDescending { it.lastBeatAt }
    }

    /** Queues [msg] for the entry's next beat, unless it is a bot, no longer here, or its queue (or the global one) is full. */
    fun offerPending(
        entry: LiveEntry,
        msg: PendingProactive,
    ): ProactiveResult =
        synchronized(entry) {
            when {
                entry.bot -> ProactiveResult.BOT
                entries[entry.visitKey] !== entry -> ProactiveResult.NOT_LIVE // removed meanwhile: its count would never be given back
                entry.pending.size >= MAX_PENDING_PER_VISITOR -> ProactiveResult.QUEUE_FULL
                pendingTotal.incrementAndGet() > MAX_PENDING_TOTAL -> {
                    pendingTotal.decrementAndGet()
                    ProactiveResult.QUEUE_FULL
                }
                else -> {
                    entry.pending.addLast(msg)
                    ProactiveResult.QUEUED
                }
            }
        }

    /** Takes all the entry's waiting messages, oldest first, and frees their places in the global count. */
    fun drainPending(entry: LiveEntry): List<PendingProactive> =
        synchronized(entry) {
            val msgs = entry.pending.toList()
            entry.pending.clear()
            pendingTotal.addAndGet(-msgs.size)
            msgs
        }

    /**
     * Puts messages that [drainPending] took but could not be delivered back in front of the entry's queue, in their order. They were
     * admitted once, so the caps are not checked again (the overshoot is at most what was drained); an entry that is gone gets nothing back.
     */
    fun requeuePending(
        entry: LiveEntry,
        msgs: List<PendingProactive>,
    ) {
        if (msgs.isEmpty()) return
        synchronized(entry) {
            if (entries[entry.visitKey] !== entry) return
            entry.pending.addAll(0, msgs)
            pendingTotal.addAndGet(msgs.size)
        }
    }

    /** Counts one visit of [siteId] flagged as a bot, for the once-a-minute security log. Callers pass validated site ids, so there are at most 7 keys. */
    fun noteBot(siteId: String) {
        botCounts.compute(siteId) { _, n -> (n ?: AtomicInteger()).also { it.incrementAndGet() } }
    }

    /** The bots flagged per site since the last drain; the counts start again from zero. */
    fun drainBotCounts(): Map<String, Int> {
        val drained = HashMap<String, Int>()
        for (siteId in botCounts.keys.toList()) {
            val n = botCounts.remove(siteId)?.get() ?: 0
            if (n > 0) drained[siteId] = n
        }
        return drained
    }

    fun size(): Int = entries.size

    /** How many visits were refused because the registry was full of live people. */
    fun rejected(): Long = rejectedCount.get()

    private fun refresh(
        existing: LiveEntry,
        resumed: LiveEntry,
    ) {
        synchronized(existing) {
            existing.lastBeatAt = resumed.lastBeatAt
            existing.bot = existing.bot || resumed.bot
        }
    }

    /** When the registry is full: expired entries go first, then the oldest bot or staff entry. */
    private fun makeRoom() {
        if (entries.size >= MAX_ENTRIES) evictExpired()
        if (entries.size >= MAX_ENTRIES) entries.values.filter { it.bot || it.staff }.minByOrNull { it.lastBeatAt }?.let { drop(it) }
    }

    /** Removes the entry and gives its waiting messages back to the global count. False if it was not there (any more). */
    private fun drop(entry: LiveEntry): Boolean =
        synchronized(entry) {
            if (!entries.remove(entry.visitKey, entry)) return false
            pendingTotal.addAndGet(-entry.pending.size)
            entry.pending.clear()
            true
        }

    private fun visitorOf(e: LiveEntry) =
        LiveVisitor(
            visitKey = e.visitKey,
            visitId = e.visitId,
            visitorKey = e.visitorKey,
            visitorId = e.visitorId,
            siteId = e.siteId,
            currentPath = e.currentPath,
            currentTitle = e.currentTitle,
            pageType = e.pageType,
            boatSlug = e.boatSlug,
            currentPageSince = e.currentPageSince,
            visitStartedAt = e.startedAt,
            lastBeatAt = e.lastBeatAt,
            returning = e.returning,
            countryCode = e.countryCode,
            ip = e.ip,
            locale = e.locale,
            deviceClass = e.deviceClass,
            browser = e.browser,
            os = e.os,
            channel = e.channel,
            fromSiteId = e.fromSiteId,
            visible = e.visible,
            pageCount = e.pageCount,
            pendingProactive = e.pending.size,
        )

    companion object {
        const val MAX_ENTRIES = 5_000
        val EVICT_AFTER: Duration = Duration.ofSeconds(60)
        const val MAX_PENDING_PER_VISITOR = 3
        const val MAX_PENDING_TOTAL = 500
    }
}
