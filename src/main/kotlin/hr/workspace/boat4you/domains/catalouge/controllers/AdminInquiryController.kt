package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.InquiryBasicDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryUpdateDto
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService
import hr.workspace.boat4you.domains.catalouge.services.InquiryQueryingService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/admin/inquiries")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminInquiryController(
    private val inquiryMutationService: InquiryMutationService,
    private val inquiryQueryingService: InquiryQueryingService,
) {
    @GetMapping
    fun getInquiries(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) statuses: List<InquiryStatus>?,
        @PageableDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): ResponseEntity<PagedModel<InquiryBasicDto>> {
        return ResponseEntity.ok(
            PagedModel(
                inquiryQueryingService.getAllInquiries(
                    search,
                    statuses,
                    pageable,
                ),
            ),
        )
    }

    @GetMapping("/{id}")
    fun getInquiries(
        @PathVariable id: Long,
    ): ResponseEntity<InquiryDetailsDto> {
        return ResponseEntity.ok(inquiryQueryingService.getInquiryById(id))
    }

    @PatchMapping("/{id}")
    fun updateStatus(
        @PathVariable id: Long,
        @RequestBody @Valid inquiryDto: InquiryUpdateDto,
    ): ResponseEntity<Unit> {
        return ResponseEntity.ok(inquiryMutationService.updateInquiry(id, inquiryDto))
    }
}
