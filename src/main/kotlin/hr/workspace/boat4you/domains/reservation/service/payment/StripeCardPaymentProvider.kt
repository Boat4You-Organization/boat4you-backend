package hr.workspace.boat4you.domains.reservation.service.payment

import hr.workspace.boat4you.domains.payment.mollie.KlarnaNotAvailableException
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import org.springframework.stereotype.Component

/**
 * Stripe hosted Checkout behind the provider seam: a thin adapter over the unchanged [StripePaymentService], mapped
 * exactly like `PublicStripePaymentController` / `StripePaymentController` (session id, `PAYMENT_PENDING`,
 * `session.url`). Stripe has no localised return URL here, so `locale` is ignored.
 */
@Component
class StripeCardPaymentProvider(
    private val stripePaymentService: StripePaymentService,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
) : CardPaymentProvider {
    override val provider: CardProvider = CardProvider.STRIPE

    override fun createCheckout(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        locale: String?,
        klarna: KlarnaBillingRequest?,
    ): CheckoutSessionDto {
        // Klarna exists on the Mollie path only; never fall back to a card checkout the payer did not ask for.
        if (klarna != null) throw KlarnaNotAvailableException("provider")
        val session = stripePaymentService.initiatePayment(reservationId, payFullAmount, paymentPhaseId, idempotencyKey)
        return CheckoutSessionDto(
            sessionIdOrOrderCode = session.id,
            status = CheckoutSessionStatusEnum.PAYMENT_PENDING,
            redirectUrl = session.url,
        )
    }

    /**
     * `null` when no payment phase carries [ref] as its Stripe session id (the old `/payments/stripe` status endpoint
     * answers SUCCESS for an unknown id; the provider-neutral one answers 404 instead).
     */
    override fun checkStatus(ref: String): CheckoutSessionDto? {
        if (paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(ref).isEmpty()) return null
        return stripePaymentService.checkPaymentStatus(ref)
    }
}
