package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class InquiryBasicDto(
    val id: Long,
    val yachtId: Long?,
    val yachtName: String?,
    val location: String?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val name: String?,
    val surname: String?,
    val email: String,
    val phone: String?,
    val status: InquiryStatus,
    val createdAt: LocalDateTime,
)
