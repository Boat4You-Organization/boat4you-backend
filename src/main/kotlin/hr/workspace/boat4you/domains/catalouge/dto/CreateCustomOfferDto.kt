package hr.workspace.boat4you.domains.catalouge.dto

import jakarta.validation.constraints.Email
import java.time.LocalDate

data class CreateCustomOfferDto(
    val inquiryId: Long?,
    @field:Email
    val email: String?,
    val yachtIds: Set<Long>,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val did: List<String>,
    val message: String?,
)
