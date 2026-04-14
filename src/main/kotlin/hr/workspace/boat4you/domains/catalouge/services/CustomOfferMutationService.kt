package hr.workspace.boat4you.domains.catalouge.services

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.common.services.UrlShortener
import hr.workspace.boat4you.domains.catalouge.dto.CreateCustomOfferDto
import hr.workspace.boat4you.domains.catalouge.dto.CustomOfferDto
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.jpa.CustomOffer
import hr.workspace.boat4you.domains.catalouge.jpa.CustomOfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CustomOfferMutationService(
    private val inquiryRepository: InquiryRepository,
    private val customOfferRepository: CustomOfferRepository,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /***
     * Method handles creating custom offer based on an inquiry or based on admin provided email
     */
    @Transactional
    fun createNewCustomOffer(customOfferDto: CreateCustomOfferDto): CustomOfferDto {
        val shortUrlHash = findUniqueShortUrl()

        val customOffer = CustomOffer()
        customOffer.createdAt = LocalDateTime.now()
        customOffer.shortUrl = shortUrlHash
        customOffer.longUrl = generateLongUrl(customOfferDto)
        customOffer.request = objectMapper.writeValueAsString(customOfferDto)
        val inquiry =
            if (customOfferDto.inquiryId != null) {
                inquiryRepository.findByIdOrNull(customOfferDto.inquiryId)
            } else {
                null
            }
        if (inquiry != null) {
            inquiry.status = InquiryStatus.ANSWERED
            inquiryRepository.saveAndFlush(inquiry)
        }
        val email =
            if (inquiry != null) {
                inquiry.email
            } else {
                customOfferDto.email
            }

        if (!customOfferDto.email.isNullOrBlank()) {
            val user = userRepository.findByEmail(customOfferDto.email)
            customOffer.user = user
            customOffer.email = customOfferDto.email
        }

        if (email.isNullOrBlank()) {
            log.error("Unable to determine email for custom offer")
            throw IllegalArgumentException("Unable to determine email for custom offer")
        }
        val shortUrl = "$serverHostPublic/custom-offers/$shortUrlHash"
        sendCustomOfferEmail(customOfferDto, shortUrl, email)

        customOfferRepository.saveAndFlush(customOffer)

        return CustomOfferDto(customOffer.id!!, shortUrlHash, shortUrl)
    }

    private fun findUniqueShortUrl(): String {
        while (true) {
            val shortUrl = UrlShortener.generateShortKey()
            val exists = customOfferRepository.findByShortUrl(shortUrl)
            if (exists == null) {
                return shortUrl
            }
        }
    }

    private fun generateLongUrl(customOfferDto: CreateCustomOfferDto): String {
        var longUrl = ""
        customOfferDto.did.forEach { did ->
            longUrl += "did=$did&"
        }
        customOfferDto.yachtIds.forEach { yachtId ->
            longUrl += "yid=$yachtId&"
        }
        if (customOfferDto.dateFrom != null) {
            longUrl += "startDate=${customOfferDto.dateFrom}&"
        }
        if (customOfferDto.dateTo != null) {
            longUrl += "endDate=${customOfferDto.dateTo}&"
        }

        return longUrl
    }

    fun sendCustomOfferEmail(
        customOfferDto: CreateCustomOfferDto,
        shortUrl: String,
        email: String,
    ) {
        var message = "Dear User, please follow the link below to get your personalized offer. <br>"
        if (customOfferDto.message != null) {
            message += "Note from Admin: ${customOfferDto.message}<br>"
        }
        message +=
            """
            <!--[if mso]><v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="urn:schemas-microsoft-com:office:word" style="height:51px; v-text-anchor:middle;" arcsize="10%" stroke="f" fillcolor="#2856ff"><w:anchorlock/><center style="color:#f6f6f6;font-family: 'Raleway',sans-serif;"><![endif]-->
              <a href="$shortUrl" target="_blank" style="
                    text-decoration: none;
                    color: #2856ff;
                    font-family: 'Raleway', sans-serif;
                    font-size: 16px;
                    font-weight: 700;
                  ">
                <span style="line-height: 120%">Click here</span>
              </a>
            """.trimIndent()

        val variables =
            mapOf(
                "message" to message,
                "shortUrl" to shortUrl,
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(email),
            subject = "Your custom offer",
            templateName = "email/customOffer",
            variables = variables,
        )
    }
}
