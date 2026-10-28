package hr.workspace.boat4you.domains.contactcenter.service

import java.time.Instant
import java.time.LocalDate

/** The `utm_*` parameters of a landing URL, each already clipped; `null` when the URL carried none. */
data class Utm(
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val term: String? = null,
    val content: String? = null,
)

/**
 * A validated `/visit` call (see `TrackerInputValidator`): the visitor key is canonical lower case, every text is clipped, [landing] and
 * [referrer] are rebuilt by `TrackerUrlPolicy`, [ccSrc] is a known site id. [landingParamNames] are the names of all query parameters of
 * the landing URL, for attribution. [ip] is the client address and [userAgent] is at most 400 characters.
 */
data class VisitCommand(
    val siteId: String,
    val visitorKey: String,
    val ip: String,
    val userAgent: String?,
    val landing: CleanUrl? = null,
    val referrer: CleanReferrer? = null,
    val utm: Utm = Utm(),
    val gclid: String? = null,
    val fbclid: String? = null,
    val landingParamNames: Set<String> = emptySet(),
    val handoffToken: String? = null,
    val locale: String? = null,
    val viewportW: Int? = null,
    val viewportH: Int? = null,
    val webdriver: Boolean = false,
    val staff: Boolean = false,
    val ccSrc: String? = null,
)

/** A validated page view: [url] and [path] come from `TrackerUrlPolicy`, [pageType] and [boatSlug] from `PageTypeClassifier`. */
data class PageViewIn(
    val clientId: String,
    val url: String,
    val path: String,
    val title: String?,
    val pageType: String,
    val boatSlug: String?,
    val viewedAt: Instant,
)

/** The criteria of one search on a site's own search form, each field already range-checked or null. */
data class SiteSearchCriteria(
    val countryCode: String?,
    val location: String?,
    val did: Long?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val persons: Int?,
    val cabins: Int?,
    val minPriceEur: Int?,
    val maxPriceEur: Int?,
    val vesselType: String?,
    val equipment: String?,
    val resultCount: Int?,
    val totalAvailable: Int?,
)

/** A validated event. [payloadJson] is a flat JSON object of short strings or null; [search] is set only for a `SEARCH` event. */
data class EventIn(
    val clientId: String,
    val type: String,
    val pvClientId: String?,
    val label: String?,
    val target: String?,
    val payloadJson: String?,
    val search: SiteSearchCriteria?,
    val consentWithdrawn: Boolean,
    val createdAt: Instant,
)

/** A validated `/beat` call. [dropped] counts the page views and events that were refused one by one. */
data class BeatCommand(
    val siteId: String,
    val visitorKey: String,
    val visitKey: String,
    val visible: Boolean = true,
    val pageViews: List<PageViewIn> = emptyList(),
    val events: List<EventIn> = emptyList(),
    val dropped: Int = 0,
)

/** A validated `/handoff` call: the visit on [siteId] asks for a single-use token for [toSiteId]. */
data class HandoffCommand(
    val siteId: String,
    val visitorKey: String,
    val visitKey: String,
    val toSiteId: String,
)
