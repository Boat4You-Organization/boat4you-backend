package hr.workspace.boat4you.domains.reservation.service.payment

import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto

/**
 * The card payment provider of ONLINE-BOOKING payments (`/payments/card`): Mollie (`MollieBookingPaymentService`).
 * The phases a checkout pays and the amount (surcharge included) come from `CardChargeCalculator`.
 */
interface CardPaymentProvider {
    /**
     * Starts a hosted checkout for [reservationId] and returns its reference (`sessionIdOrOrderCode`), status
     * `PAYMENT_PENDING` and the `redirectUrl` the browser must open. `payFullAmount` / `paymentPhaseId` select the
     * phases exactly as `CardChargeCalculator.select` (400 for an invalid combination). [locale] is the web's
     * next-intl locale (a provider may use it for its return URL; others ignore it).
     */
    fun createCheckout(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        locale: String?,
        /** Non-null = a Klarna checkout with this billing address. */
        klarna: KlarnaBillingRequest? = null,
    ): CheckoutSessionDto

    /** Status of a checkout this provider created; `null` when the provider does not know [ref]. */
    fun checkStatus(ref: String): CheckoutSessionDto?
}
