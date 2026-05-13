package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/public/inquiries")
class InquiryController(
    private val inquiryMutationService: InquiryMutationService,
) {
    @PostMapping()
    fun createInquiry(
        @RequestBody @Valid inquiryDto: InquiryDto,
        request: HttpServletRequest,
    ): ResponseEntity<Unit> {
        // Pass request through so the mutation layer can resolve the
        // active brand from `X-Boat4You-Brand` (or Origin/Referer
        // fallback) and dispatch the broker notification email to that
        // brand's recipient mailbox.
        return ResponseEntity.ok(inquiryMutationService.createNewInquiry(inquiryDto, request))
    }

    // The two debug endpoints that used to live here (`/{id}/email-preview`
    // and `/{id}/send-test`) were extracted on 2026-05-13 into
    // AdminInquiryDebugController under `/admin/inquiries/debug/...` with
    // @Profile("dev") + @PreAuthorize SYSTEM_ADMIN. They closed F1-067
    // (HIGH PII leak) and F1-068 (CRIT anonymous email-bombing) — both
    // were anonymous-reachable here because the controller sits under
    // the `/public/**` permitAll rule in SecurityConfiguration.
}
