package hr.workspace.boat4you.domains.pr.web

import hr.workspace.boat4you.domains.pr.dto.ImportResult
import hr.workspace.boat4you.domains.pr.dto.PrAlertFeedDto
import hr.workspace.boat4you.domains.pr.dto.PrAlertFeedRequest
import hr.workspace.boat4you.domains.pr.dto.PrContactDto
import hr.workspace.boat4you.domains.pr.dto.PrContactFilter
import hr.workspace.boat4you.domains.pr.dto.PrContactRequest
import hr.workspace.boat4you.domains.pr.dto.PrFollowUpRequest
import hr.workspace.boat4you.domains.pr.dto.PrMentionDto
import hr.workspace.boat4you.domains.pr.dto.PrMentionStatusRequest
import hr.workspace.boat4you.domains.pr.dto.PrOutletDto
import hr.workspace.boat4you.domains.pr.dto.PrOutletRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchDto
import hr.workspace.boat4you.domains.pr.dto.PrPitchStatusRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchUpdateRequest
import hr.workspace.boat4you.domains.pr.dto.PrPlacementDto
import hr.workspace.boat4you.domains.pr.dto.PrPlacementRequest
import hr.workspace.boat4you.domains.pr.dto.PrStudyDto
import hr.workspace.boat4you.domains.pr.dto.PrStudyRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrMentionStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.links.PrLinkCheckService
import hr.workspace.boat4you.domains.pr.mentions.PrMentionService
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import org.springframework.data.web.PagedModel
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PR module admin API (1:1 pitches from press@boat4you.com to journalists), SYSTEM_ADMIN only. Errors: 400 / 404 / 409
 * with `{code, message}` (PrErrorAdvice). Markets are ISO-3166 alpha-2; `UK` and English country names are accepted.
 */
@RestController
@RequestMapping("/admin/pr")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminPrController(
    private val contacts: PrContactService,
    private val studies: PrStudyService,
    private val pitches: PrPitchService,
    private val placements: PrPlacementService,
    private val linkCheck: PrLinkCheckService,
    private val mentions: PrMentionService,
) {
    // ---- Outlets ----

    @GetMapping("/outlets")
    fun outlets(): List<PrOutletDto> = contacts.listOutlets()

    @PostMapping("/outlets")
    fun createOutlet(
        @RequestBody body: PrOutletRequest,
    ): PrOutletDto = contacts.createOutlet(body)

    @PatchMapping("/outlets/{id}")
    fun updateOutlet(
        @PathVariable id: Long,
        @RequestBody body: PrOutletRequest,
    ): PrOutletDto = contacts.updateOutlet(id, body)

    // ---- Contacts ----

    @GetMapping("/contacts")
    fun contacts(
        @RequestParam(required = false) market: String?,
        @RequestParam(required = false) status: PrContactStatus?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "100") size: Int,
    ): PagedModel<PrContactDto> = PagedModel(contacts.listContacts(PrContactFilter(market, status, q, page, size)))

    @PostMapping("/contacts")
    fun createContact(
        @RequestBody body: PrContactRequest,
    ): PrContactDto = contacts.createContact(body)

    @PatchMapping("/contacts/{id}")
    fun updateContact(
        @PathVariable id: Long,
        @RequestBody body: PrContactRequest,
    ): PrContactDto = contacts.updateContact(id, body)

    /** The CSV file as the body (`text/csv`, UTF-8), header row first; see PrContactService.importCsv. */
    @PostMapping("/contacts/import", consumes = ["text/csv", "text/plain"])
    fun import(
        @RequestBody body: ByteArray,
    ): ImportResult = contacts.importCsv(body.toString(Charsets.UTF_8))

    @PostMapping("/contacts/{id}/opt-out")
    fun optOut(
        @PathVariable id: Long,
    ): PrContactDto = contacts.optOut(id)

    @PostMapping("/contacts/{id}/bounce")
    fun bounce(
        @PathVariable id: Long,
    ): PrContactDto = contacts.markBounced(id)

    // ---- Studies ----

    @GetMapping("/studies")
    fun studies(): List<PrStudyDto> = studies.list()

    @PostMapping("/studies")
    fun createStudy(
        @RequestBody body: PrStudyRequest,
    ): PrStudyDto = studies.create(body)

    @PatchMapping("/studies/{id}")
    fun updateStudy(
        @PathVariable id: Long,
        @RequestBody body: PrStudyRequest,
    ): PrStudyDto = studies.update(id, body)

    // ---- Pitches (drafts, sending, hand-typed statuses) ----

    @GetMapping("/pitches")
    fun pitches(
        @RequestParam(required = false) studyId: Long?,
        @RequestParam(required = false) status: PrPitchStatus?,
    ): List<PrPitchDto> = pitches.list(studyId, status)

    @PostMapping("/pitches")
    fun createPitch(
        @RequestBody body: PrPitchDraftRequest,
    ): PrPitchDto = pitches.createDraft(body)

    /** Subject / body of a DRAFT only (409 PITCH_NOT_DRAFT otherwise). */
    @PatchMapping("/pitches/{id}")
    fun updatePitch(
        @PathVariable id: Long,
        @RequestBody body: PrPitchUpdateRequest,
    ): PrPitchDto = pitches.updateDraft(id, body)

    @PostMapping("/pitches/{id}/follow-up")
    fun followUp(
        @PathVariable id: Long,
        @RequestBody body: PrFollowUpRequest,
    ): PrPitchDto = pitches.createFollowUpDraft(id, body.body)

    /**
     * Sends the pitch from press@ now: 409 MAIL_DISABLED / MAIL_NOT_CONFIGURED / a send-rule code / ALREADY_SENDING_OR_SENT.
     * An SMTP failure answers 200 with status FAILED and `error`; PATCH .../status to DRAFT is the retry.
     */
    @PostMapping("/pitches/{id}/send")
    fun send(
        @PathVariable id: Long,
    ): PrPitchDto = pitches.send(id)

    /** REPLIED / DECLINED / PUBLISHED / BOUNCED after sending; DRAFT only from FAILED (retry). */
    @PatchMapping("/pitches/{id}/status")
    fun setPitchStatus(
        @PathVariable id: Long,
        @RequestBody body: PrPitchStatusRequest,
    ): PrPitchDto {
        val status = body.status ?: throw PrException.badRequest("STATUS_NOT_ALLOWED", "status is required")
        return pitches.setStatus(id, status, body.replyNote)
    }

    // ---- Placements (articles that used a study) and their link checks ----

    @GetMapping("/placements")
    fun placements(
        @RequestParam(required = false) studyId: Long?,
    ): List<PrPlacementDto> = placements.list(studyId)

    /** 409 PLACEMENT_EXISTS for an article already listed; the page is not fetched here ("Provjeri sad" does that). */
    @PostMapping("/placements")
    fun createPlacement(
        @RequestBody body: PrPlacementRequest,
    ): PrPlacementDto = placements.create(body)

    @PatchMapping("/placements/{id}")
    fun updatePlacement(
        @PathVariable id: Long,
        @RequestBody body: PrPlacementRequest,
    ): PrPlacementDto = placements.update(id, body)

    /** "Provjeri sad": fetches the article now (up to ~15 s) and stores the result. */
    @PostMapping("/placements/{id}/check")
    fun checkPlacement(
        @PathVariable id: Long,
    ): PrPlacementDto = linkCheck.check(id)

    @PostMapping("/placements/{id}/clear-alert")
    fun clearPlacementAlert(
        @PathVariable id: Long,
    ): PrPlacementDto = placements.clearAlert(id)

    // ---- Alert feeds (Google Alerts / Talkwalker RSS) and the mentions found in them ----

    @GetMapping("/feeds")
    fun feeds(): List<PrAlertFeedDto> = mentions.listFeeds()

    /** name and url required (400 FEED_NAME_REQUIRED / FEED_URL_INVALID); 409 FEED_EXISTS for a URL already listed. */
    @PostMapping("/feeds")
    fun createFeed(
        @RequestBody body: PrAlertFeedRequest,
    ): PrAlertFeedDto = mentions.createFeed(body)

    @PatchMapping("/feeds/{id}")
    fun updateFeed(
        @PathVariable id: Long,
        @RequestBody body: PrAlertFeedRequest,
    ): PrAlertFeedDto = mentions.updateFeed(id, body)

    /** Newest first; status and linksToUs filter when given. */
    @GetMapping("/mentions")
    fun mentions(
        @RequestParam(required = false) status: PrMentionStatus?,
        @RequestParam(required = false) linksToUs: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PagedModel<PrMentionDto> = PagedModel(mentions.listMentions(status, linksToUs, page, size))

    @PatchMapping("/mentions/{id}")
    fun setMentionStatus(
        @PathVariable id: Long,
        @RequestBody body: PrMentionStatusRequest,
    ): PrMentionDto {
        val status = body.status ?: throw PrException.badRequest("STATUS_REQUIRED", "status is required")
        return mentions.setMentionStatus(id, status)
    }
}
