package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class InquiryDetailsDto(
    val id: Long,
    val yachtId: Long?,
    val yachtName: String?,
    val locationId: String?,
    val location: String?,
    val countryCode: String?,
    val mainImage: Long?,
    val modelName: String?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val name: String?,
    val surname: String?,
    val email: String,
    val phone: String?,
    val status: InquiryStatus,
    val message: String,
    val createdAt: LocalDateTime,
)
