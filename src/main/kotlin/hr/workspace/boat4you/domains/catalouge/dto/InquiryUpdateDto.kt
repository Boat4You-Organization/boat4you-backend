package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus

data class InquiryUpdateDto(
    val status: InquiryStatus,
)
