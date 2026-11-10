package hr.workspace.boat4you.domains.reservation.service.payment

import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto

/**
 * A card payment provider for ONLINE-BOOKING payments (`/payments/card`). Only the provider differs; the phases a
 * checkout pays and the amount (surcharge included) come from `CardChargeCalculator` for every provider.
 * [CardPaymentRouter] picks the provider for a new checkout from `application.payment.card-provider`.
 */
interface CardPaymentProvider {
    val provider: CardProvider

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
        /** Non-null = a Klarna checkout with this billing address (Mollie only; 422 from any other provider). */
        klarna: KlarnaBillingRequest? = null,
    ): CheckoutSessionDto

    /** Status of a checkout this provider created; `null` when the provider does not know [ref]. */
    fun checkStatus(ref: String): CheckoutSessionDto?
}

/** Values of `application.payment.card-provider` (`stripe` | `mollie`, case-insensitive). */
enum class CardProvider {
    STRIPE,
    MOLLIE,
    ;

    companion object {
        /** `null` for anything else than `stripe` / `mollie` (blank included). */
        fun fromConfig(value: String?): CardProvider? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
