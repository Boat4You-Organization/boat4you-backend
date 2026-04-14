package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService
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
    ): ResponseEntity<Unit> {
        return ResponseEntity.ok(inquiryMutationService.createNewInquiry(inquiryDto))
    }
}
