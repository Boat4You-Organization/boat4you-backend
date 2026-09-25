package hr.workspace.boat4you.domains.review.controllers

import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.ReviewStatus
import hr.workspace.boat4you.domains.review.dto.AdminReviewDto
import hr.workspace.boat4you.domains.review.dto.AdminReviewStatusRequest
import hr.workspace.boat4you.domains.review.service.ReviewService
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
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
}
