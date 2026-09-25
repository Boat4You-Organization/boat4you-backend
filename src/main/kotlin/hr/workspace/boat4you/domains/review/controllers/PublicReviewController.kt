package hr.workspace.boat4you.domains.review.controllers

import hr.workspace.boat4you.domains.review.dto.ReviewFormDto
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitResultDto
import hr.workspace.boat4you.domains.review.service.ReviewService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Guest review form behind the magic link from the review request e-mail (no login; the token is the credential,
 * so responses are never cached). `/public/` paths are permitAll in SecurityConfiguration; POST is rate-limited per
 * IP in PublicEndpointRateLimiter. Errors: 400 invalid fields, 404 unknown / expired link, 409 edit window over.
 */
@RestController
@RequestMapping("/public/reviews/request")
class PublicReviewController(
    private val reviewService: ReviewService,
) {
    @Operation(summary = "Review form context for a review link (kind, boat, dates, base, first name, existing review)")
    @GetMapping("/{token}")
    fun getForm(
        @PathVariable token: String,
    ): ResponseEntity<ReviewFormDto> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviewService.getForm(token))

    @Operation(
        summary = "Submit or edit the review of a review link",
        description = "201 = review stored; 200 = existing review changed (allowed for 24 h after the first submit); " +
            "400 = invalid fields; 404 = unknown or expired link; 409 = the 24 h edit window has passed.",
    )
    @PostMapping("/{token}")
    fun submit(
        @PathVariable token: String,
        @RequestBody body: ReviewSubmitRequest,
    ): ResponseEntity<ReviewSubmitResultDto> {
        val outcome = reviewService.submit(token, body)
        return ResponseEntity
            .status(if (outcome.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(outcome.result)
    }
}
