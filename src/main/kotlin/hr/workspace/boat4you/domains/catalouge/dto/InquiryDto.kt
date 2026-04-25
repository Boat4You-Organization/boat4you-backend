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
)
