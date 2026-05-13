package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.branding.BrandResolver
import hr.workspace.boat4you.domains.catalouge.services.InquiryEmailService
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Dev-only inquiry debugging endpoints. Split out of the public
 * `InquiryController` 2026-05-13 to close F1-067 (HIGH PII leak via
 * email-preview render) and F1-068 (CRIT anonymous email-bombing via
 * force send-test).
 *
 * Both endpoints originally sat at `/public/inquiries/{id}/...` with
 * the inline comment "should be tightened or removed before go-live"
 * — they were dev-iteration utilities (eyeball the rendered
 * notification template, force-send despite global email-disabled
 * flag) that never got tightened, and Spring Security `permitAll`s
 * the `/public` path tree, so any unauthenticated client could fire
 * the broker email loop arbitrarily often.
 *
 * Triple-defense, same shape as DevEquipmentSyncController:
 * 1. `@Profile("dev")` — bean is not registered outside dev profile,
 *    so a prod deploy never loads these endpoints at all.
 * 2. `/admin/inquiries/debug` URL prefix — does not match the
 *    `permitAll` rule on the `/public` path tree, so even if the dev
 *    profile is misapplied to a prod env, JWT auth is still required.
 * 3. `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` at class level —
 *    role check on top of authentication, matching the convention
 *    in the sibling AdminInquiryController.
 *
 * Verb mapping is preserved from the originals: GET for the read-only
 * preview render, POST for the side-effecting force-send.
 */
@RestController
@Profile("dev")
@RequestMapping("/admin/inquiries/debug")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminInquiryDebugController(
    private val inquiryEmailService: InquiryEmailService,
    private val brandResolver: BrandResolver,
) {
    /** Render the new-inquiry notification email as raw HTML for browser
     *  preview while iterating on the template. Optional `?brand=` query
     *  picks up the per-brand logo, From line, recipient mailbox,
     *  support contacts, accent colour out of BrandRegistry. Defaults
     *  to Boat4You when unspecified. */
    @GetMapping("/{id}/email-preview", produces = [MediaType.TEXT_HTML_VALUE])
    fun previewInquiryEmail(
        @PathVariable id: Long,
        @RequestParam(name = "brand", required = false) brandId: String?,
    ): ResponseEntity<String> {
        val brand = brandId?.let { brandResolver.resolveById(it) }
        return ResponseEntity.ok(inquiryEmailService.renderPreview(id, brand))
    }

    /** Force-send the new-inquiry notification email for an existing
     *  inquiry id, bypassing the global `application.email.enabled`
     *  flag. Used to verify the rendered email lands in the recipient
     *  inbox in dev without flipping email on for every other flow at
     *  the same time. */
    @PostMapping("/{id}/send-test")
    fun sendTestInquiryEmail(
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        inquiryEmailService.sendNewInquiryNotificationById(id, force = true)
        return ResponseEntity.ok(mapOf("status" to "queued", "inquiryId" to id))
    }
}
