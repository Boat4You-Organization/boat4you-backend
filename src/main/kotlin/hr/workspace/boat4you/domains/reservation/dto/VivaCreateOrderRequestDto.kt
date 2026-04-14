package hr.workspace.boat4you.domains.reservation.dto

data class VivaCreateOrderRequestDto(
    val amount: Long,
    val sourceCode: String,
    val customerTrns: String? = null,
    val merchantTrns: String? = null,
    val customer: VivaCustomerDto? = null,
    val tags: List<String>? = null,
)

data class VivaCustomerDto(
    val email: String? = null,
    val fullName: String? = null,
    val requestLang: String? = null, // e.g., "en-GB"
)
