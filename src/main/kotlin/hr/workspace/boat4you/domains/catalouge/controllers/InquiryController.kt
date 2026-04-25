package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.services.InquiryEmailService
import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/public/inquiries")
class InquiryController(
    private val inquiryMutationService: InquiryMutationService,
    private val inquiryEmailService: InquiryEmailService,
) {
    @PostMapping()
    fun createInquiry(
        @RequestBody @Valid inquiryDto: InquiryDto,
    ): ResponseEntity<Unit> {
        return ResponseEntity.ok(inquiryMutationService.createNewInquiry(inquiryDto))
    }

    /** Render the new-inquiry notification email as raw HTML for browser
     *  preview while we iterate on the template. Sits under `/public/`
     *  only so it's reachable without an admin token in dev — no inquiry
     *  data leaves what we already accept in the create endpoint, but
     *  this should still be moved behind auth (or removed entirely) before
     *  go-live. See SESSION_HANDOFF for the migration plan. */
    @GetMapping("/{id}/email-preview", produces = [MediaType.TEXT_HTML_VALUE])
    fun previewInquiryEmail(
        @PathVariable id: Long,
    ): ResponseEntity<String> {
        return ResponseEntity.ok(inquiryEmailService.renderPreview(id))
    }

    /** Force-send the new-inquiry notification email for an existing
     *  inquiry id, bypassing the global `application.email.enabled` flag.
     *  Lets Mario verify the rendered email lands in the recipient inbox
     *  in dev without flipping email on for every other flow (booking
     *  confirmations, password resets) at the same time. Open while the
     *  preview endpoint is open — both should be tightened or removed
     *  before go-live. */
    @PostMapping("/{id}/send-test")
    fun sendTestInquiryEmail(
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        inquiryEmailService.sendNewInquiryNotificationById(id, force = true)
        return ResponseEntity.ok(mapOf("status" to "queued", "inquiryId" to id))
    }
}
