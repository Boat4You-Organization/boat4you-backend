package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * One `/beat` of the widget (design 2.2, 2.3): the live state of the visit, the appended page views and events, a throttled
 * `last_seen_at`, and the proactive messages that wait for the visitor. The live list is updated in memory first, so a beat never waits
 * for the database except for the one lookup [VisitService.resolveForBeat] does when the registry has forgotten the visit.
 *
 * A beat that carries a withdrawn analytics consent (WEB-15) takes the visitor out of the live list at once and stores nothing of it.
 */
@Service
class BeatService(
    private val visits: VisitService,
    private val registry: LiveVisitorRegistry,
    private val writer: TrackerWriteQueue,
    private val proactive: ProactiveMessageService,
) {
    internal var clock: Clock = Clock.systemUTC()

    /** What the controller answers: [UnknownVisit] is 404, [Quiet] 204, [Delivered] 200 with the messages. */
    sealed interface BeatOutcome {
        data object UnknownVisit : BeatOutcome

        data object Quiet : BeatOutcome

        data class Delivered(
            val delivery: ProactiveDelivery,
        ) : BeatOutcome
    }

    // One linear pass over the rules of a beat (consent, page-view rate, throttled touch, delivery); an unknown visit, a withdrawn consent
    // and a bot each end it before anything is queued.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun beat(cmd: BeatCommand): BeatOutcome {
        val entry = visits.resolveForBeat(cmd.visitKey, cmd.visitorKey, cmd.siteId) ?: return BeatOutcome.UnknownVisit
        val now = clock.instant()
        if (cmd.events.any { it.consentWithdrawn }) { // design 2.1 / WEB-15: gone from the live list at once
            registry.remove(entry.visitKey)
            writer.touch(entry.visitId, now, null)
            return BeatOutcome.Quiet
        }
        var botReason: String? = null
        val touchNow: Boolean
        synchronized(entry) {
            entry.lastBeatAt = now
            entry.visible = cmd.visible
            cmd.pageViews.forEach { pv ->
                if (entry.notePageView(pv.clientId.hashCode(), now.toEpochMilli()) == LiveEntry.PvNote.NEW_OVER_RATE && !entry.bot) {
                    entry.bot = true
                    botReason = BotFilter.PV_RATE
                }
            }
            cmd.pageViews.maxByOrNull { it.viewedAt }?.let { last ->
                if (last.path != entry.currentPath) entry.currentPageSince = last.viewedAt
                entry.currentPath = last.path
                entry.currentTitle = last.title
                entry.pageType = last.pageType
                entry.boatSlug = last.boatSlug
            }
            touchNow = botReason != null || Duration.between(entry.seenPersistedAt, now) >= SEEN_FLUSH_EVERY
            if (touchNow) entry.seenPersistedAt = now
        }
        cmd.pageViews.forEach { writer.offer(PageViewRow(entry.visitId, it)) }
        cmd.events.forEach { writer.offer(EventRow(entry.visitId, entry.siteId, it)) }
        if (touchNow) writer.touch(entry.visitId, now, botReason)
        if (botReason != null) registry.noteBot(entry.siteId)
        if (entry.bot) return BeatOutcome.Quiet
        return proactive.deliver(entry)?.let { BeatOutcome.Delivered(it) } ?: BeatOutcome.Quiet
    }

    companion object {
        /** `cc_visit.last_seen_at` is written at most this often per visit, plus once immediately when a bot is flagged. */
        val SEEN_FLUSH_EVERY: Duration = Duration.ofSeconds(15)
    }
}
