package hr.workspace.boat4you.domains.pr.dto

import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrMentionStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrStudyStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// The admin's PR types, field for field. Markets are ISO-3166 alpha-2 (GB, not UK); emails are stored normalised.

data class PrOutletDto(
    val id: Long,
    val name: String,
    val market: String,
    val tier: Int,
    val sectionUrl: String?,
    val deskContact: String?,
    val notes: String?,
    val createdAt: Instant,
)

/** POST needs name, market and tier; PATCH changes only the fields sent (a blank optional text clears it). */
data class PrOutletRequest(
    val name: String? = null,
    val market: String? = null,
    val tier: Int? = null,
    val sectionUrl: String? = null,
    val deskContact: String? = null,
    val notes: String? = null,
)

data class PrContactDto(
    val id: Long,
    val outletId: Long?,
    val outletName: String?,
    val name: String?,
    val role: String?,
    val email: String,
    val market: String?,
    val emailSourceUrl: String?,
    val emailFoundOn: LocalDate?,
    val status: PrContactStatus,
    val recentArticleUrl: String?,
    val notes: String?,
    val lastContactedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * POST needs email (market defaults to the outlet's); PATCH changes only the fields sent (a blank optional text clears
 * it). status may be ACTIVE, DO_NOT_CONTACT, CONSENT_REQUESTED or CONSENTED; OPTED_OUT comes only from opt-out / bounce.
 */
data class PrContactRequest(
    val outletId: Long? = null,
    val name: String? = null,
    val role: String? = null,
    val email: String? = null,
    val market: String? = null,
    val emailSourceUrl: String? = null,
    val emailFoundOn: LocalDate? = null,
    val status: PrContactStatus? = null,
    val recentArticleUrl: String? = null,
    val notes: String? = null,
)

data class PrContactFilter(
    val market: String? = null,
    val status: PrContactStatus? = null,
    val q: String? = null,
    val page: Int = 0,
    val size: Int = 100,
)

/** created / updated count contacts (an unchanged row counts as neither); skipped = "row N: CODE: detail", one per row. */
data class ImportResult(
    val created: Int,
    val updated: Int,
    val skipped: List<String>,
)

/** siteId is one of the 7 BrandRegistry site ids (boat4you, catamaran-croatia, ...). */
data class PrStudyDto(
    val id: Long,
    val number: Int,
    val siteId: String,
    val title: String,
    val pageUrl: String?,
    val status: PrStudyStatus,
    val publishedOn: LocalDate?,
    val notes: String?,
    val createdAt: Instant,
)

/** POST needs number, siteId and title (status defaults to DRAFT); PATCH changes only the fields sent (a blank optional text clears it). */
data class PrStudyRequest(
    val number: Int? = null,
    val siteId: String? = null,
    val title: String? = null,
    val pageUrl: String? = null,
    val status: PrStudyStatus? = null,
    val publishedOn: LocalDate? = null,
    val notes: String? = null,
)

/**
 * One pitch with what the admin list shows next to it. decision is "ALLOWED" or the PrComplianceRules block code, worked
 * out now (a draft may become sendable later, e.g. a follow-up after 5 days); footer is the text the backend appends
 * to every sent body (PrFooter), shown read-only under the editor.
 */
data class PrPitchDto(
    val id: Long,
    val studyId: Long?,
    val contactId: Long,
    val contactEmail: String,
    val contactName: String?,
    val outletName: String?,
    val parentPitchId: Long?,
    val template: PrPitchTemplate,
    val subject: String,
    val body: String,
    val status: PrPitchStatus,
    val decision: String,
    val footer: String,
    val messageId: String?,
    val sentAt: Instant?,
    val error: String?,
    val replyNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A new main pitch (FOLLOW_UP is not a template here: follow-ups come from POST .../pitches/{id}/follow-up). */
data class PrPitchDraftRequest(
    val studyId: Long? = null,
    val contactId: Long? = null,
    val template: PrPitchTemplate? = null,
    val subject: String? = null,
    val body: String? = null,
)

/** PATCH of a DRAFT: only the fields sent change. */
data class PrPitchUpdateRequest(
    val subject: String? = null,
    val body: String? = null,
)

data class PrFollowUpRequest(
    val body: String? = null,
)

/** status is REPLIED, DECLINED, PUBLISHED or BOUNCED; replyNote (typed in by hand) replaces the stored one when given. */
data class PrPitchStatusRequest(
    val status: PrPitchStatus? = null,
    val replyNote: String? = null,
)

/**
 * An article that used one of our studies, with the result of its last link check. lastHttpStatus is the status of
 * the last hop, -1 when nothing was fetched (address refused, timeout, network error). alert stays true until POST
 * .../clear-alert; alertReason is e.g. "OK_FOLLOW→LINK_MISSING". domainRank is Open PageRank (0-10) or null.
 */
data class PrPlacementDto(
    val id: Long,
    val studyId: Long?,
    val outletId: Long?,
    val outletName: String?,
    val pitchId: Long?,
    val articleUrl: String,
    val publishedOn: LocalDate?,
    val targetUrl: String?,
    val anchorText: String?,
    val linkState: PrLinkState,
    val lastGoodState: PrLinkState?,
    val lastHttpStatus: Int?,
    val lastCheckedAt: Instant?,
    val alert: Boolean,
    val alertReason: String?,
    val domainRank: BigDecimal?,
    val createdAt: Instant,
)

/**
 * POST needs articleUrl (http/https, unique: 409 PLACEMENT_EXISTS); studyId / outletId / pitchId must exist (404).
 * PATCH changes only the fields sent (a blank optional text clears it); a new articleUrl resets the check result.
 */
data class PrPlacementRequest(
    val studyId: Long? = null,
    val outletId: Long? = null,
    val pitchId: Long? = null,
    val articleUrl: String? = null,
    val publishedOn: LocalDate? = null,
    val targetUrl: String? = null,
    val anchorText: String? = null,
)

/** A Google Alerts / Talkwalker / other RSS or Atom feed read every 6 h; lastError is the last run's problem (null when it went fine). */
data class PrAlertFeedDto(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val lastFetchedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
)

/**
 * POST needs name and url (http/https, at most 1000 characters, unique: 409 FEED_EXISTS); enabled defaults to true.
 * PATCH changes only the fields sent; a new url clears lastFetchedAt and lastError.
 */
data class PrAlertFeedRequest(
    val name: String? = null,
    val url: String? = null,
    val enabled: Boolean? = null,
)

/**
 * A page that names us, found in a feed. url is normalised (Google redirect unwrapped, utm_* / fbclid / gclid and the
 * #fragment dropped, host lowercase); source is GOOGLE_ALERTS, TALKWALKER or RSS. linksToUs: true / false from one
 * fetch of the page when it was found, null when the page refused us (403, 429, ...) or could not be fetched.
 */
data class PrMentionDto(
    val id: Long,
    val url: String,
    val title: String?,
    val source: String,
    val feedId: Long?,
    val feedName: String?,
    val linksToUs: Boolean?,
    val status: PrMentionStatus,
    val foundAt: Instant,
)

/** status: NEW, ASK_FOR_LINK, ASKED, LINKED or IGNORED (400 STATUS_REQUIRED without one). */
data class PrMentionStatusRequest(
    val status: PrMentionStatus? = null,
)

/**
 * One study's row on "Pregled", counting main pitches only (follow-ups are not counted): sent = has sentAt; replied =
 * REPLIED, DECLINED or PUBLISHED; published = PUBLISHED; placements = articles listed for the study.
 */
data class StudyKpi(
    val studyId: Long,
    val number: Int,
    val title: String,
    val sent: Int,
    val replied: Int,
    val published: Int,
    val placements: Int,
)

/**
 * GET /admin/pr/overview. perStudy has every study, by number. liveFollow / liveNofollow = placements whose last check
 * found an OK_FOLLOW / OK_NOFOLLOW link; alerts = placements with an open alert; mentionsNew = mentions still NEW;
 * mentionsUnlinked = mentions known not to link to us (linksToUs false) that are NEW, ASK_FOR_LINK or ASKED;
 * followUpsDue = main pitches still SENT (no reply typed in) sent 5 or more days ago that have no follow-up yet.
 */
data class PrOverviewDto(
    val perStudy: List<StudyKpi>,
    val liveFollow: Int,
    val liveNofollow: Int,
    val alerts: Int,
    val mentionsNew: Int,
    val mentionsUnlinked: Int,
    val followUpsDue: Int,
)
