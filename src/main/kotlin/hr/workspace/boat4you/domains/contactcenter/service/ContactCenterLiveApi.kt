package hr.workspace.boat4you.domains.contactcenter.service

import java.time.Instant

/** What P2 (the admin live view) reads: the people on the sites right now, without bots and staff. Implemented by [LiveVisitorRegistry]. */
interface LiveVisitorQuery {
    fun liveSnapshot(): List<LiveVisitor>
}

/** What P2 calls to send a proactive message to a visitor on the site. Implemented by `ProactiveMessageService` (Task 11). */
interface ProactiveMessaging {
    fun enqueueProactive(
        visitorKey: String,
        text: String,
        adminUserId: Long,
    ): ProactiveResult
}

/** P3 calls this from the chat endpoints when the widget sends its visitorKey and visitId (implemented by `VisitService`, Task 10). */
interface ChatVisitLinker {
    fun linkChatSession(
        chatSessionId: Long,
        siteId: String,
        visitorKey: String,
        visitKey: String,
    ): Boolean
}

enum class ProactiveResult { QUEUED, NOT_LIVE, BOT, QUEUE_FULL, INVALID_TEXT }

/** One person on one site right now, as the admin live view shows them. [visitKey] and [visitorKey] are the public credentials, [visitId] and [visitorId] the row ids. */
data class LiveVisitor(
    val visitKey: String,
    val visitId: Long,
    val visitorKey: String,
    val visitorId: Long,
    val siteId: String,
    val currentPath: String?,
    val currentTitle: String?,
    val pageType: String?,
    val boatSlug: String?,
    val currentPageSince: Instant?,
    val visitStartedAt: Instant,
    val lastBeatAt: Instant,
    val returning: Boolean,
    val countryCode: String?,
    val ip: String?,
    val locale: String?,
    val deviceClass: String,
    val browser: String?,
    val os: String?,
    val channel: String,
    val fromSiteId: String?,
    val visible: Boolean,
    val pageCount: Int,
    val pendingProactive: Int,
)
