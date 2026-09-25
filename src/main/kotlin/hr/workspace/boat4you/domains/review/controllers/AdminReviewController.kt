package hr.workspace.boat4you.domains.review.controllers

import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.ReviewStatus
import hr.workspace.boat4you.domains.review.dto.AdminReviewDto
import hr.workspace.boat4you.domains.review.dto.AdminReviewStatusRequest
import hr.workspace.boat4you.domains.review.service.ReviewInvitationService
import hr.workspace.boat4you.domains.review.service.ReviewResendOutcome
import hr.workspace.boat4you.domains.review.service.ReviewService
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.data.web.PagedModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Review moderation (admin panel). Reviews arrive as NEW; nothing is published without an admin. */
@RestController
@RequestMapping("/admin/reviews")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminReviewController(
    private val reviewService: ReviewService,
    private val invitationService: ReviewInvitationService,
) {
    @Operation(summary = "List reviews, newest first; optional kind (BOOKING/YACHT) and status (NEW/PUBLISHED/HIDDEN) filters")
    @GetMapping
    fun list(
        @RequestParam(required = false) kind: ReviewKind?,
        @RequestParam(required = false) status: ReviewStatus?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int,
    ): ResponseEntity<PagedModel<AdminReviewDto>> = ResponseEntity.ok(PagedModel(reviewService.listForAdmin(kind, status, page, size)))

    @Operation(summary = "Moderate a review: {\"status\": \"PUBLISHED\" | \"HIDDEN\" | \"NEW\"}")
    @PatchMapping("/{id}")
    fun setStatus(
        @PathVariable id: Long,
        @RequestBody body: AdminReviewStatusRequest,
    ): ResponseEntity<AdminReviewDto> {
        val adminUserId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
        return ResponseEntity.ok(reviewService.setStatus(id, body.status, adminUserId))
    }

    @Operation(
        summary = "Re-send a review request (kind=BOOKING|YACHT) with a fresh link",
        description = "For customers whose request could not be used (e.g. mailed while the web review page was " +
            "missing). Replaces the unanswered request and e-mails a new link. 200 SENT; 409 DISABLED " +
            "(application.reviews.enabled=false) or ALREADY_REVIEWED; 404 NOT_ELIGIBLE (not a real, confirmed, paid " +
            "booking of a reachable, not opted-out customer). Nothing is changed unless the outcome is SENT.",
    )
    @PostMapping("/requests/{reservationId}/resend")
    fun resendRequest(
        @PathVariable reservationId: Long,
        @RequestParam kind: ReviewKind,
    ): ResponseEntity<Map<String, String>> {
        val outcome = invitationService.resend(reservationId, kind)
        val status =
            when (outcome) {
                ReviewResendOutcome.SENT -> HttpStatus.OK
                ReviewResendOutcome.DISABLED, ReviewResendOutcome.ALREADY_REVIEWED -> HttpStatus.CONFLICT
                ReviewResendOutcome.NOT_ELIGIBLE -> HttpStatus.NOT_FOUND
            }
        return ResponseEntity.status(status).body(mapOf("outcome" to outcome.name))
    }
}
