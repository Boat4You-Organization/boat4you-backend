package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryUpdateDto
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class InquiryMutationService(
    private val inquiryRepository: InquiryRepository,
    private val yachtRepository: YachtRepository,
) {
    @Transactional
    fun createNewInquiry(inquiryDto: InquiryDto) {
        val inquiry = Inquiry()
        inquiry.createdAt = LocalDateTime.now()

        if (inquiryDto.yachtId != null) {
            val yacht = yachtRepository.findById(inquiryDto.yachtId).orElseThrow { YachtDoesNotExistException() }
            inquiry.yacht = yacht
        }

        inquiry.dateFrom = inquiryDto.dateFrom
        inquiry.dateTo = inquiryDto.dateTo
        inquiry.name = inquiryDto.name
        inquiry.surname = inquiryDto.surname
        inquiry.email = inquiryDto.email
        inquiry.phone = inquiryDto.phone
        inquiry.message = inquiryDto.message
        inquiry.status = InquiryStatus.NEW

        inquiryRepository.saveAndFlush(inquiry)
    }

    @Transactional
    fun updateInquiry(
        id: Long,
        inquiryDto: InquiryUpdateDto,
    ) {
        val inquiry =
            inquiryRepository
                .findById(id)
                .orElseThrow { throw IllegalArgumentException("Inquiry with id $id not found") }
        inquiry.status = inquiryDto.status

        inquiryRepository.saveAndFlush(inquiry)
    }
}
