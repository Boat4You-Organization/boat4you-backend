package hr.workspace.boat4you.domains.contactcenter.controllers

/**
 * The wire format of the tracker calls (`/visit`, `/beat`, `/handoff`). Every field is nullable or defaulted so that a missing field is
 * a validation result (`TrackerInputValidator`), never a deserialization error. Nothing here is trusted until the validator has run.
 */
data class ViewportDto(
    val w: Int? = null,
    val h: Int? = null,
)

data class VisitRequest(
    val siteId: String? = null,
    val visitorKey: String? = null,
    val landingUrl: String? = null,
    val referrer: String? = null,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val utmTerm: String? = null,
    val utmContent: String? = null,
    val gclid: String? = null,
    val fbclid: String? = null,
    val handoffToken: String? = null,
    val locale: String? = null,
    val viewport: ViewportDto? = null,
    val webdriver: Boolean? = null,
    val staff: Boolean? = null,
)

/** [visitId] is the visit's public key (a UUID v4), [visitorId] the canonical visitor key; neither is a database id (BACKEND-29). */
data class VisitResponse(
    val visitId: String,
    val visitorId: String,
    val beatSeconds: Int,
    val minBeatGapSeconds: Int,
)

/** [at] is the client time in epoch milliseconds. */
data class PageViewDto(
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val at: Long? = null,
)

/** [pvId] is the client id of the page view the event happened on; [at] is the client time in epoch milliseconds. */
data class EventDto(
    val id: String? = null,
    val type: String? = null,
    val pvId: String? = null,
    val label: String? = null,
    val target: String? = null,
    val payload: Map<String, Any?>? = null,
    val at: Long? = null,
)

/** The list elements are nullable because Jackson puts a JSON `null` element into a Kotlin list; the validator drops and counts it. */
data class BeatRequest(
    val siteId: String? = null,
    val visitorKey: String? = null,
    val visitId: String? = null,
    val visible: Boolean? = null,
    val pageViews: List<PageViewDto?>? = null,
    val events: List<EventDto?>? = null,
)

data class ProactiveMessageDto(
    val id: Long,
    val text: String,
    val createdAt: String,
    val author: String = "HUMAN",
)

data class BeatResponse(
    val proactiveMessages: List<ProactiveMessageDto>,
    val chatToken: String?,
)

data class HandoffRequest(
    val siteId: String? = null,
    val visitorKey: String? = null,
    val visitId: String? = null,
    val toSiteId: String? = null,
)

data class HandoffResponse(
    val token: String,
    val expiresInSeconds: Int,
)
