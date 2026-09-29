package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.branding.Brand
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class InquiryMutationService(
    private val inquiryRepository: InquiryRepository,
    private val yachtRepository: YachtRepository,
    private val inquiryEmailService: InquiryEmailService,
    private val brandResolver: BrandResolver,
) {
    private val log = LoggerFactory.getLogger(InquiryMutationService::class.java)

    companion object {
        /** A second identical submit inside this window is the same inquiry (double tap, retry, back button). */
        val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(10)

        /**
         * Identity of one submit (Mario 27.9.2026: one inquiry per submit). Phone (digits only) and name are part
         * of it, so a guest who corrects a wrong number or name within the window is not swallowed; a double tap
         * or a network retry sends identical data and collapses. Same key as the boat4you-web guard.
         */
        internal fun submitKey(
            email: String?,
            yachtId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
            phone: String?,
            name: String?,
            surname: String?,
            message: String?,
        ): String =
            listOf(
                email?.trim()?.lowercase().orEmpty(),
                yachtId?.toString().orEmpty(),
                dateFrom?.toString().orEmpty(),
                dateTo?.toString().orEmpty(),
                phone?.filter { it.isDigit() }.orEmpty(),
                name?.trim()?.lowercase().orEmpty(),
                surname?.trim()?.lowercase().orEmpty(),
                message?.trim()?.replace(Regex("\\s+"), " ").orEmpty(),
            ).joinToString("\u001F")
    }

    @Transactional
    fun createNewInquiry(
        inquiryDto: InquiryDto,
        request: HttpServletRequest? = null,
    ) {
        val key =
            submitKey(
                inquiryDto.email, inquiryDto.yachtId, inquiryDto.dateFrom, inquiryDto.dateTo,
                inquiryDto.phone, inquiryDto.name, inquiryDto.surname, inquiryDto.message,
            )
        inquiryRepository.lockSubmitKey(key)
        val duplicate =
            inquiryRepository
                .findRecentByEmail(inquiryDto.email.trim().lowercase(), LocalDateTime.now().minus(DUPLICATE_WINDOW))
                .firstOrNull {
                    submitKey(it.email, it.yacht?.id, it.dateFrom, it.dateTo, it.phone, it.name, it.surname, it.message) == key
                }
        if (duplicate != null) {
            log.info("Duplicate inquiry submit ignored (same as id=${duplicate.id} within $DUPLICATE_WINDOW)")
            return
        }

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

        // Sent inline, as before: EmailService.sendEmail already defers the SMTP submit to this transaction's
        // afterCommit, so a rolled-back save notifies nobody. Do NOT wrap this in an afterCommit of our own —
        // EmailService would then register its deferral inside afterCommit, which Spring never runs, and no
        // inquiry e-mail would go out (review 29.9.2026). The advisory lock is released at commit, after this row
        // is written, so a concurrent duplicate finds it.
        sendInquiryEmails(inquiry, request?.let(brandResolver::resolve))
    }

    private fun sendInquiryEmails(
        inquiry: Inquiry,
        brand: Brand?,
    ) {
        // Send broker notification immediately. Brand drives the recipient
        // mailbox + From line + logo — every catamaran-* / europe-yachts
        // brand currently routes through info@boat4you.com via the registry
        // placeholders, so leads land in the master inbox until per-brand
        // mailboxes are provisioned. Failure to send must NOT roll the
        // inquiry back — the lead is already saved, email is best-effort.
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
