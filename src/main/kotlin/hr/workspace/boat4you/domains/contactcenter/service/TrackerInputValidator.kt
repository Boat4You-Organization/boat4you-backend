package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.controllers.BeatRequest
import hr.workspace.boat4you.domains.contactcenter.controllers.EventDto
import hr.workspace.boat4you.domains.contactcenter.controllers.HandoffRequest
import hr.workspace.boat4you.domains.contactcenter.controllers.PageViewDto
import hr.workspace.boat4you.domains.contactcenter.controllers.VisitRequest
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The result of validating a tracker call: the command to run, or the HTTP [status] and the `{"error": ...}` field name to answer with. */
sealed interface Checked<out T> {
    data class Ok<T>(
        val value: T,
    ) : Checked<T>

    data class Rejected(
        val status: Int,
        val error: String,
    ) : Checked<Nothing>
}

/**
 * Turns the untrusted body of `/visit`, `/beat` and `/handoff` into commands the services can trust. The checks run in a fixed order and
 * the first failure is the answer: site id, `Origin`, visitor key, visit id (`/beat` and `/handoff`), then the call's own fields.
 *
 * A bad single item of a beat (page view or event) is dropped and counted, not an error; only a page view of a foreign host or too many
 * items refuse the whole beat. All text is bounded by [TrackerText.clip], all times are bounded to the server clock, and URLs are
 * rebuilt by [TrackerUrlPolicy] (never echoed). No public id is a database id (BACKEND-29).
 */
@Component
class TrackerInputValidator(
    private val urls: TrackerUrlPolicy,
    private val objectMapper: ObjectMapper,
) {
    internal var clock: Clock = Clock.systemUTC()

    fun visit(
        req: VisitRequest,
        origin: String?,
        ip: String,
        userAgent: String?,
    ): Checked<VisitCommand> {
        val siteId = site(req.siteId, origin).valueOr { return it }
        val visitorKey = uuid(req.visitorKey, "visitor_key").valueOr { return it }
        val landing =
            when (val c = req.landingUrl?.let { urls.clean(siteId, it) }) {
                null -> null
                is UrlCheck.Ok -> c.url
                UrlCheck.Secret -> null
                UrlCheck.ForeignHost -> return Checked.Rejected(403, "host")
                UrlCheck.Invalid -> return Checked.Rejected(400, "landing_url")
            }
        return Checked.Ok(
            VisitCommand(
                siteId = siteId,
                visitorKey = visitorKey,
                ip = ip,
                userAgent = TrackerText.clip(userAgent, MAX_USER_AGENT),
                landing = landing,
                referrer = urls.referrer(req.referrer),
                utm =
                    Utm(
                        source = bodyOrLanding(req.utmSource, landing, "utm_source"),
                        medium = bodyOrLanding(req.utmMedium, landing, "utm_medium"),
                        campaign = bodyOrLanding(req.utmCampaign, landing, "utm_campaign"),
                        term = bodyOrLanding(req.utmTerm, landing, "utm_term"),
                        content = bodyOrLanding(req.utmContent, landing, "utm_content"),
                    ),
                gclid = bodyOrLanding(req.gclid, landing, "gclid"),
                fbclid = bodyOrLanding(req.fbclid, landing, "fbclid"),
                landingParamNames = landing?.params?.keys ?: emptySet(),
                handoffToken = TrackerText.clip(req.handoffToken, MAX_HANDOFF_TOKEN),
                locale = req.locale?.takeIf { it.length <= MAX_LOCALE && LOCALE.matches(it) },
                viewportW = req.viewport?.w?.takeIf { it in VIEWPORT },
                viewportH = req.viewport?.h?.takeIf { it in VIEWPORT },
                webdriver = req.webdriver == true,
                staff = req.staff == true,
                ccSrc = landing?.params?.get("cc_src")?.takeIf(SiteIds::isKnown),
            ),
        )
    }

    fun beat(
        req: BeatRequest,
        origin: String?,
    ): Checked<BeatCommand> {
        val siteId = site(req.siteId, origin).valueOr { return it }
        val visitorKey = uuid(req.visitorKey, "visitor_key").valueOr { return it }
        val visitKey = uuid(req.visitId, "visit_id").valueOr { return it }
        val pageViewsIn = req.pageViews.orEmpty()
        val eventsIn = req.events.orEmpty()
        if (pageViewsIn.size > MAX_PAGE_VIEWS || eventsIn.size > MAX_EVENTS) return Checked.Rejected(400, "too_many_items")
        val now = clock.instant()
        var dropped = 0
        val pageViews = ArrayList<PageViewIn>(pageViewsIn.size)
        for (dto in pageViewsIn) {
            val pageView = if (dto == null) null else pageViewOf(siteId, dto, now).valueOr { return it }
            if (pageView == null) dropped++ else pageViews += pageView
        }
        val events = ArrayList<EventIn>(eventsIn.size)
        for (dto in eventsIn) {
            val event = dto?.let { eventOf(it, now) }
            if (event == null) dropped++ else events += event
        }
        return Checked.Ok(BeatCommand(siteId, visitorKey, visitKey, req.visible ?: true, pageViews, events, dropped))
    }

    fun handoff(
        req: HandoffRequest,
        origin: String?,
    ): Checked<HandoffCommand> {
        val siteId = site(req.siteId, origin).valueOr { return it }
        val visitorKey = uuid(req.visitorKey, "visitor_key").valueOr { return it }
        val visitKey = uuid(req.visitId, "visit_id").valueOr { return it }
        val toSiteId = req.toSiteId?.takeIf { SiteIds.isKnown(it) && it != siteId } ?: return Checked.Rejected(400, "to_site")
        return Checked.Ok(HandoffCommand(siteId, visitorKey, visitKey, toSiteId))
    }

    /** The claimed site, if it is one of the seven and the `Origin` header is a host of it. */
    private fun site(
        siteId: String?,
        origin: String?,
    ): Checked<String> =
        when {
            siteId == null || !SiteIds.isKnown(siteId) -> Checked.Rejected(400, "site")
            !urls.originMatches(origin, siteId) -> Checked.Rejected(403, "origin")
            else -> Checked.Ok(siteId)
        }

    /** [raw] as a canonical (lower case) UUID v4, or `400 `[error]. */
    private fun uuid(
        raw: String?,
        error: String,
    ): Checked<String> = raw?.lowercase()?.takeIf { UUID_V4.matches(it) }?.let { Checked.Ok(it) } ?: Checked.Rejected(400, error)

    /** An attribution field: the body's value, else the landing URL's parameter [name], each clipped to 200. */
    private fun bodyOrLanding(
        body: String?,
        landing: CleanUrl?,
        name: String,
    ): String? = TrackerText.clip(body, MAX_ATTRIBUTION) ?: TrackerText.clip(landing?.params?.get(name), MAX_ATTRIBUTION)

    /** `Ok(null)` drops the page view; a [Checked.Rejected] (foreign host) refuses the whole beat. */
    private fun pageViewOf(
        siteId: String,
        dto: PageViewDto,
        now: Instant,
    ): Checked<PageViewIn?> {
        val id = dto.id?.takeIf { CLIENT_ID.matches(it) } ?: return Checked.Ok(null)
        val page =
            when (val c = urls.clean(siteId, dto.url)) {
                is UrlCheck.Ok -> c.url
                UrlCheck.ForeignHost -> return Checked.Rejected(403, "host")
                UrlCheck.Secret, UrlCheck.Invalid -> return Checked.Ok(null)
            }
        val type = PageTypeClassifier.classify(siteId, page.path)
        return Checked.Ok(PageViewIn(id, page.url, page.path, TrackerText.clip(dto.title, MAX_TITLE), type.type, type.boatSlug, timeOf(dto.at, now)))
    }

    /** The event, or null when its id or type is not acceptable. */
    private fun eventOf(
        dto: EventDto,
        now: Instant,
    ): EventIn? {
        val id = dto.id?.takeIf { CLIENT_ID.matches(it) } ?: return null
        val type = dto.type?.takeIf { it in EVENT_TYPES } ?: return null
        val payload = payloadOf(dto.payload)
        return EventIn(
            clientId = id,
            type = type,
            pvClientId = dto.pvId?.takeIf { CLIENT_ID.matches(it) },
            label = TrackerText.clip(dto.label, MAX_LABEL),
            target = TrackerText.clip(dto.target, MAX_TARGET),
            payloadJson = payload?.let { objectMapper.writeValueAsString(it) },
            search = if (type == "SEARCH") searchOf(payload, now) else null,
            consentWithdrawn = type == "CONSENT" && payload?.get("analytics") == "false",
            createdAt = timeOf(dto.at, now),
        )
    }

    /**
     * A flat payload of at most 10 keys with plain names and values of at most 200 characters, else null (the event is kept without
     * it). Null values are skipped; a nested value, or any key that is not a plain name, refuses the whole payload.
     */
    internal fun payloadOf(raw: Map<String, Any?>?): Map<String, String>? {
        if (raw.isNullOrEmpty() || raw.size > MAX_PAYLOAD_KEYS) return null
        val out = LinkedHashMap<String, String>()
        for ((k, value) in raw) {
            if (!PAYLOAD_KEY.matches(k)) return null
            val s =
                when (value) {
                    null -> continue
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> return null
                }
            TrackerText.clip(s, MAX_PAYLOAD_VALUE)?.let { out[k] = it }
        }
        return out.ifEmpty { null }
    }

    /** The criteria of a site search from its normalized [payload]; each value that fails its check is null (BACKEND-11 for the place). */
    private fun searchOf(
        payload: Map<String, String>?,
        now: Instant,
    ): SiteSearchCriteria? {
        if (payload == null) return null
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val dates = today.minusYears(1)..today.plusYears(3)

        fun date(key: String): LocalDate? = payload[key]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it in dates }

        fun int(
            key: String,
            range: IntRange,
        ): Int? = payload[key]?.toIntOrNull()?.takeIf { it in range }
        return SiteSearchCriteria(
            countryCode = payload["countryCode"]?.takeIf { COUNTRY.matches(it) },
            location = payload["location"]?.takeIf { LOCATION.matches(it) },
            did = payload["did"]?.toLongOrNull()?.takeIf { it > 0 },
            startDate = date("startDate"),
            endDate = date("endDate"),
            persons = int("persons", 1..MAX_PEOPLE),
            cabins = int("cabins", 1..MAX_PEOPLE),
            minPriceEur = int("minPrice", 0..MAX_PRICE),
            maxPriceEur = int("maxPrice", 0..MAX_PRICE),
            vesselType = payload["vesselType"]?.takeIf { VESSEL_TYPE.matches(it) },
            equipment = TrackerText.clip(payload["equipment"], MAX_PAYLOAD_VALUE),
            resultCount = int("resultCount", 0..MAX_COUNT),
            totalAvailable = int("totalAvailable", 0..MAX_COUNT),
        )
    }

    /** The client time [at] (epoch milliseconds) when it lies within [now] − 60 min .. [now] + 5 min, else [now]. */
    private fun timeOf(
        at: Long?,
        now: Instant,
    ): Instant {
        val t = at?.let { Instant.ofEpochMilli(it) } ?: return now
        return if (t.isBefore(now.minus(MAX_AGE)) || t.isAfter(now.plus(MAX_AHEAD))) now else t
    }

    private inline fun <T> Checked<T>.valueOr(reject: (Checked.Rejected) -> Nothing): T =
        when (this) {
            is Checked.Ok -> value
            is Checked.Rejected -> reject(this)
        }

    companion object {
        const val MAX_PAGE_VIEWS = 20
        const val MAX_EVENTS = 50
        val EVENT_TYPES = setOf("CLICK", "SEARCH", "CHAT_OPEN", "GREETING_SHOWN", "QUICK_REPLY", "NUDGE_SHOWN", "NUDGE_CLICK", "NUDGE_CLOSE", "CONSENT", "INQUIRY")

        private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,40}$")
        private val PAYLOAD_KEY = Regex("^[A-Za-z0-9_]{1,40}$")
        private val LOCALE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val LOCATION = Regex("""^[\p{L}\p{N} ,.'’()-]{2,60}$""") // BACKEND-11 charset
        private val VESSEL_TYPE = Regex("^[A-Z_]{2,30}$")
        private val VIEWPORT = 0..10_000

        private const val MAX_USER_AGENT = 400
        private const val MAX_HANDOFF_TOKEN = 100
        private const val MAX_LOCALE = 10
        private const val MAX_ATTRIBUTION = 200
        private const val MAX_TITLE = 300
        private const val MAX_LABEL = 200
        private const val MAX_TARGET = 300
        private const val MAX_PAYLOAD_KEYS = 10
        private const val MAX_PAYLOAD_VALUE = 200
        private const val MAX_PEOPLE = 60
        private const val MAX_PRICE = 10_000_000
        private const val MAX_COUNT = 100_000
        private val MAX_AGE: Duration = Duration.ofMinutes(60)
        private val MAX_AHEAD: Duration = Duration.ofMinutes(5)
    }
}
