package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.InquiryBasicDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryUpdateDto
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime

@Service
class InquiryQueryingService(
    private val inquiryRepository: InquiryRepository,
) {
    fun getAllInquiries(
        search: String?,
        statuses: List<InquiryStatus>?,
        pageable: Pageable,
    ): Page<InquiryBasicDto> {
        return inquiryRepository
            .findAllByParamsForAdmin(search, statuses, pageable)
            .map { it.toDto() }
    }

    fun getInquiryById(id: Long): InquiryDetailsDto {
        val inquiry =
            inquiryRepository
                .findById(id)
                .orElseThrow { IllegalArgumentException("Inquiry with id $id does not exist") }
        return inquiry.toDetailsDto()
    }
}
