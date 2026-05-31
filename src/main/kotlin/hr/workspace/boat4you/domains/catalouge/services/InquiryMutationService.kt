package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.branding.BrandResolver
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryUpdateDto
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class InquiryMutationService(
    private val inquiryRepository: InquiryRepository,
    private val yachtRepository: YachtRepository,
    private val inquiryEmailService: InquiryEmailService,
    private val brandResolver: BrandResolver,
) {
    private val log = LoggerFactory.getLogger(InquiryMutationService::class.java)

    @Transactional
    fun createNewInquiry(
        inquiryDto: InquiryDto,
        request: HttpServletRequest? = null,
    ) {
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

        // Send broker notification immediately. Brand drives the recipient
        // mailbox + From line + logo — every catamaran-* / europe-yachts
        // brand currently routes through info@boat4you.com via the registry
        // placeholders, so leads land in the master inbox until per-brand
        // mailboxes are provisioned. Failure to send must NOT roll the
        // inquiry back — the lead is already saved, email is best-effort.
        val brand = request?.let(brandResolver::resolve)
        runCatching {
            inquiryEmailService.sendNewInquiryNotification(inquiry, brand)
        }.onFailure { log.error("Failed to dispatch new-inquiry notification for id=${inquiry.id}", it) }

        // Courtesy acknowledgement to the client ("we received your inquiry").
        // Separate runCatching so a failure here can't suppress the broker
        // notification above or roll back the saved inquiry.
        runCatching {
            inquiryEmailService.sendInquiryClientAcknowledgement(inquiry, brand)
        }.onFailure { log.error("Failed to dispatch inquiry acknowledgement for id=${inquiry.id}", it) }
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
