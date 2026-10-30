package hr.workspace.boat4you.domains.catalouge.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class InquiryDto(
    val yachtId: Long?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    @field:Size(max = 255)
    val name: String?,
    @field:Size(max = 255)
    val surname: String?,
    @field:Email
    @field:NotNull
    @field:NotBlank
    @field:Size(max = 255)
    val email: String,
    // Phone is required so we can reach the lead by call/WhatsApp — the
    // brokerage flow leans heavily on quick voice follow-ups, and a stale
    // email is a worse contact than a fresh phone number. The customer
    // form already enforces this client-side; @NotBlank rejects the
    // empty-string case the validator might miss (e.g. someone POSTing
    // straight to /public/inquiries with curl).
    @field:NotNull
    @field:NotBlank
    @field:Size(max = 63)
    val phone: String,
    @field:Size(max = 2000)
    val message: String,
    /**
     * AI call center fields (design infra/docs/ai-call-center-dizajn-2026-10-27.md §2.6), all optional: today's forms send none.
     * One of the 7 CallSite keys; an unknown key is ignored.
     */
    @field:Size(max = 40)
    val site: String? = null,
    @field:Size(max = 5)
    val locale: String? = null,
    /** The mandatory consent checkbox (calls incl. Nika's AI call, recording). */
    val contactConsent: Boolean? = null,
    @field:Size(max = 20)
    val consentTextVersion: String? = null,
    /** A sister site already sent the inquiry e-mails itself (nodemailer); the backend must not send them again. */
    val emailAlreadySent: Boolean? = null,
    /** Structured form fields read by the call center: people, cabins, budgetEur, weekPriceEur, region, boatType, crew, kids. */
    val details: Map<String, Any?>? = null,
)
