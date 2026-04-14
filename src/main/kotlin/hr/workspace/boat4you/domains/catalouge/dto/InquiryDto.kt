package hr.workspace.boat4you.domains.catalouge.dto

import jakarta.validation.constraints.Email
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
    @field:Size(max = 255)
    val email: String,
    @field:Size(max = 63)
    val phone: String?,
    @field:Size(max = 2000)
    val message: String,
)
