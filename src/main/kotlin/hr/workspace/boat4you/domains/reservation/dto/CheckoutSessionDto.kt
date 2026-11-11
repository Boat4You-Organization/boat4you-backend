package hr.workspace.boat4you.domains.reservation.dto

data class CheckoutSessionDto(
    val sessionIdOrOrderCode: String,
    val status: CheckoutSessionStatusEnum,
    /**
     * The URL the client should redirect the user to in order to complete payment.
     * Populated when a checkout is created (the provider's hosted checkout URL). Null on status-check responses
     * where no redirect is needed.
     * The web frontend reads this field as `redirectUrl` — see
     * `usePaymentSubmit.tsx` and `api/payments/create-checkout-session/route.ts`.
     */
    val redirectUrl: String? = null,
)

enum class CheckoutSessionStatusEnum {
    PAYMENT_PENDING,
    PAYMENT_SUCCESS,

    /**
     * Mollie only: the payer came back to the success page while the payment is still `open` (no attempt finished —
     * a declined card or the checkout's back link). Not paid; the web shows the "payment not completed" page.
     */
    PAYMENT_NOT_COMPLETED,
}
