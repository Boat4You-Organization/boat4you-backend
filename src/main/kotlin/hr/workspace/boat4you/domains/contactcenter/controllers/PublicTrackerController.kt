package hr.workspace.boat4you.domains.contactcenter.controllers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.contactcenter.service.BeatService
import hr.workspace.boat4you.domains.contactcenter.service.CcSettings
import hr.workspace.boat4you.domains.contactcenter.service.Checked
import hr.workspace.boat4you.domains.contactcenter.service.HandoffTokenStore
import hr.workspace.boat4you.domains.contactcenter.service.TrackerClientIp
import hr.workspace.boat4you.domains.contactcenter.service.TrackerInputValidator
import hr.workspace.boat4you.domains.contactcenter.service.TrackerSurgeGuard
import hr.workspace.boat4you.domains.contactcenter.service.VisitService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.IOException

/**
 * The public tracker API of the widget (design 2.1 to 2.5): `POST /public/cc/v1/visit`, `/beat` and `/handoff`. The browser calls these
 * directly, so nothing in a body is trusted: [TrackerInputValidator] checks it, the `Origin` header must be a host of the claimed site,
 * and no response carries a database id (BACKEND-29), a cookie (BACKEND-28) or a cacheable header (`Cache-Control: no-store`).
 *
 * Status codes: 200 with a body; 204 when the tracker is switched off (`cc.tracker.enabled`, the body is not even read), when there is
 * nothing to deliver, or when a handoff is refused; 400 `{"error":"<field>"}` for invalid input; 403 `{"error":"origin"|"host"}`;
 * 404 `{"error":"unknown_visit"}`; 413 `{"error":"too_large"}` for a body over [MAX_BODY_BYTES]; 429 `{"error":"site_busy"}` when the site
 * is over its visit budget for the minute (the per-IP 429 comes from `PublicEndpointRateLimiter` and nginx); 503 `{"error":"unavailable"}`
 * for a database or transaction error and 503 `{"error":"busy"}` when the bulkhead has no database permit within 100 ms.
 *
 * Widget contract (P3):
 * - `/visit` is called once per tab, and again only when a beat answers 404 `unknown_visit`; from then on the widget only beats.
 * - `/handoff` **issues** the single-use token on the source site (a live human visit only; a bot gets 204), and the destination site
 *   **redeems** it inside its own `/visit` (`handoffToken`). A token lives [HandoffTokenStore.TTL] and is spent on the first presentation.
 * - The 429 of the per-IP limiter and of nginx has no CORS headers: any non-2xx answer or network error means exponential backoff.
 *
 * Not authentication: everything under `/public/` is open, and the origin check is a guard against another site's script posting under our site id,
 * not against a client that forges the header.
 */
@RestController
@RequestMapping("/public/cc/v1")
@Suppress("LongParameterList") // the seven collaborators of the three endpoints
class PublicTrackerController(
    private val validator: TrackerInputValidator,
    private val visits: VisitService,
    private val beats: BeatService,
    private val handoffs: HandoffTokenStore,
    private val settings: CcSettings,
    private val objectMapper: ObjectMapper,
    private val guard: TrackerSurgeGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/visit")
    fun visit(http: HttpServletRequest): ResponseEntity<Any> =
        guarded(http, VisitRequest::class.java) { req ->
            val cmd =
                when (val checked = validator.visit(req, http.getHeader("Origin"), TrackerClientIp.of(http), http.getHeader("User-Agent"))) {
                    is Checked.Rejected -> return@guarded error(checked.status, checked.error)
                    is Checked.Ok -> checked.value
                }
            if (!guard.allowVisit(cmd.siteId)) return@guarded error(HTTP_TOO_MANY_REQUESTS, "site_busy")
            val started = visits.startOrResume(cmd)
            reply(HTTP_OK, VisitResponse(started.visitKey, started.visitorKey, VisitService.BEAT_SECONDS, VisitService.MIN_BEAT_GAP_SECONDS))
        }

    @PostMapping("/beat")
    fun beat(http: HttpServletRequest): ResponseEntity<Any> =
        guarded(http, BeatRequest::class.java) { req ->
            val cmd =
                when (val checked = validator.beat(req, http.getHeader("Origin"))) {
                    is Checked.Rejected -> return@guarded error(checked.status, checked.error)
                    is Checked.Ok -> checked.value
                }
            when (val outcome = beats.beat(cmd)) {
                BeatService.BeatOutcome.UnknownVisit -> error(HTTP_NOT_FOUND, "unknown_visit")
                BeatService.BeatOutcome.Quiet -> noContent()
                is BeatService.BeatOutcome.Delivered ->
                    reply(
                        HTTP_OK,
                        BeatResponse(
                            outcome.delivery.messages.map { ProactiveMessageDto(it.id, it.text, it.createdAt.toString()) },
                            outcome.delivery.chatToken,
                        ),
                    )
            }
        }

    @PostMapping("/handoff")
    fun handoff(http: HttpServletRequest): ResponseEntity<Any> =
        guarded(http, HandoffRequest::class.java) { req ->
            val cmd =
                when (val checked = validator.handoff(req, http.getHeader("Origin"))) {
                    is Checked.Rejected -> return@guarded error(checked.status, checked.error)
                    is Checked.Ok -> checked.value
                }
            val entry = visits.resolveForBeat(cmd.visitKey, cmd.visitorKey, cmd.siteId) ?: return@guarded error(HTTP_NOT_FOUND, "unknown_visit")
            if (entry.bot) return@guarded noContent()
            val token = handoffs.issue(cmd.visitorKey, cmd.siteId, cmd.toSiteId) ?: return@guarded noContent()
            reply(HTTP_OK, HandoffResponse(token, HandoffTokenStore.TTL.seconds.toInt()))
        }

    /**
     * The frame of all three calls, in this order: tracker off (204, body unread), body size (413), JSON (400), the database bulkhead
     * (503 `busy`), then [handle] with database errors mapped to 503. [TrackerSurgeGuard.tryEnter] sits outside the `try`, so a refused
     * entry never reaches [TrackerSurgeGuard.exit] and every granted permit is given back exactly once. [handle] runs the validator before
     * anything keyed by the claimed site (`allowVisit`), so a forged site id never reaches the guard's maps.
     */
    @Suppress("ReturnCount") // a guard clause per refusal, in the order of the doc above
    private fun <T : Any> guarded(
        http: HttpServletRequest,
        type: Class<T>,
        handle: (T) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> {
        if (!settings.trackerEnabled) return noContent()
        if (http.contentLengthLong > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val bytes = http.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val req: T =
            try {
                objectMapper.readerFor(type).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue<T?>(bytes)
            } catch (e: IOException) {
                null
            } ?: return error(HTTP_BAD_REQUEST, "json") // not JSON, not an object of this type, or the literal null
        if (!guard.tryEnter()) return error(HTTP_UNAVAILABLE, "busy") // bulkhead: at most 8 tracker calls work at once
        return try {
            handle(req)
        } catch (e: DataAccessException) {
            log.warn("cc tracker DB error: {}", e.message)
            error(HTTP_UNAVAILABLE, "unavailable")
        } catch (e: TransactionException) {
            // a Hikari pool timeout surfaces as CannotCreateTransactionException, which is no DataAccessException
            log.warn("cc tracker DB error: {}", e.message)
            error(HTTP_UNAVAILABLE, "unavailable")
        } finally {
            guard.exit()
        }
    }

    private fun reply(
        status: Int,
        body: Any,
    ): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private fun error(
        status: Int,
        error: String,
    ): ResponseEntity<Any> = reply(status, mapOf("error" to error))

    private fun noContent(): ResponseEntity<Any> = ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()

    companion object {
        const val MAX_BODY_BYTES = 32_768
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_UNAVAILABLE = 503
    }
}
