package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.branding.BrandResolver
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The inquiry forms' call fields (design §2.1, §2.6): consent, site, language, E.164 phone, details, no double e-mail. */
class InquiryConsentTest {
    private val inquiries = mock(InquiryRepository::class.java)
    private val emails = mock(InquiryEmailService::class.java)
    private val planner = mock(CallPlanner::class.java)
    private val service =
        InquiryMutationService(inquiries, mock(YachtRepository::class.java), emails, mock(BrandResolver::class.java), planner)

    private fun dto(
        site: String? = null,
        locale: String? = null,
        consent: Boolean? = null,
        version: String? = null,
        emailAlreadySent: Boolean? = null,
        details: Map<String, Any?>? = null,
        phone: String = "+39 333 123 4567",
    ) = InquiryDto(
        yachtId = null,
        dateFrom = null,
        dateTo = null,
        name = "Giulia",
        surname = "Rossi",
        email = "giulia@example.it",
        phone = phone,
        message = "Ciao",
        site = site,
        locale = locale,
        contactConsent = consent,
        consentTextVersion = version,
        emailAlreadySent = emailAlreadySent,
        details = details,
    )

    private fun saved(): Inquiry {
        val captor = ArgumentCaptor.forClass(Inquiry::class.java)
        verify(inquiries).saveAndFlush(captor.capture())
        return captor.value
    }

    @Test
    fun `an inquiry with consent stores site, language, consent version and time and the E164 phone`() {
        service.createNewInquiry(dto("italy", "it", true, "calls-consent-v1", details = mapOf("people" to 8)))
        val i = saved()
        i.site shouldBe "italy"
        i.locale shouldBe "it"
        i.contactConsent shouldBe true
        i.consentTextVersion shouldBe "calls-consent-v1"
        i.consentAt.shouldNotBeNull()
        ChronoUnit.SECONDS.between(i.consentAt, Instant.now()) shouldBe 0L
        i.phoneE164 shouldBe "+393331234567"
        i.details shouldBe mapOf("people" to 8)
    }

    @Test
    fun `an inquiry from today's forms has no consent, no site and is never callable without a plus number`() {
        service.createNewInquiry(dto(phone = "091 234 5678"))
        val i = saved()
        i.contactConsent shouldBe false
        i.consentAt.shouldBeNull()
        i.site.shouldBeNull()
        i.phoneE164.shouldBeNull()
        i.phone shouldBe "091 234 5678"
    }

    @Test
    fun `an unknown site key is not stored`() {
        service.createNewInquiry(dto(site = "evil.example"))
        saved().site.shouldBeNull()
    }

    @Test
    fun `a sister site that already e-mailed suppresses the backend e-mails`() {
        service.createNewInquiry(dto(site = "greece", consent = true, emailAlreadySent = true))
        verifyNoInteractions(emails)
    }

    @Test
    fun `without the flag the backend e-mails go out as before`() {
        service.createNewInquiry(dto())
        val inquiry = saved()
        verify(emails).sendNewInquiryNotification(inquiry, null)
        verify(emails).sendInquiryClientAcknowledgement(inquiry, null)
    }

    @Test
    fun `every saved inquiry is handed to the call planner`() {
        service.createNewInquiry(dto(site = "italy", consent = true))
        val inquiry = saved()
        verify(planner).onInquiryCreated(inquiry)
    }

    @Test
    fun `a call planner failure never stops the inquiry or its e-mails`() {
        doThrow(IllegalStateException("boom")).`when`(planner).onInquiryCreated(any(Inquiry::class.java) ?: Inquiry())
        service.createNewInquiry(dto(consent = true))
        val inquiry = saved()
        verify(emails).sendNewInquiryNotification(inquiry, null)
    }

    @Test
    fun `details over 4000 characters are refused before anything is saved`() {
        shouldThrow<ParameterValidationException> { service.createNewInquiry(dto(details = mapOf("wishes" to "x".repeat(4001)))) }
        verify(inquiries, never()).saveAndFlush(any(Inquiry::class.java) ?: Inquiry())
    }
}
