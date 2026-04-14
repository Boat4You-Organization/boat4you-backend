package hr.workspace.boat4you.domains.reservation.dto

data class CheckoutSessionDto(
    val sessionIdOrOrderCode: String,
    val status: CheckoutSessionStatusEnum,
    /**
     * The URL the client should redirect the user to in order to complete payment.
     * Populated by [StripePaymentController.createCheckoutSession] (Stripe returns
     * `session.url`). Null on status-check responses where no redirect is needed.
     * The web frontend reads this field as `redirectUrl` — see
     * `usePaymentSubmit.tsx` and `api/payments/create-checkout-session/route.ts`.
     */
    val redirectUrl: String? = null,
)

enum class CheckoutSessionStatusEnum {
    PAYMENT_PENDING,
    PAYMENT_SUCCESS,
}
