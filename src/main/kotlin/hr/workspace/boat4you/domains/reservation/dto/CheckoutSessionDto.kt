package hr.workspace.boat4you.domains.reservation.dto

data class CheckoutSessionDto(
    val sessionIdOrOrderCode: String,
    val status: CheckoutSessionStatusEnum,
)

enum class CheckoutSessionStatusEnum {
    PAYMENT_PENDING,
    PAYMENT_SUCCESS,
}
