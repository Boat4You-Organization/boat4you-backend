package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProvider
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import hr.workspace.boat4you.domains.reservation.service.payment.CardProvider
import org.springframework.stereotype.Service

/**
 * Revolut hosted checkout for ONLINE-BOOKING card payments (Revolut plan 2A.4).
 *
 * STAGE A STUB: only the provider seam is wired. A new checkout answers 503 (`CardPaymentsUnavailableException`) and
 * no reference is known (no order ledger yet), so the router falls through to Stripe for status. Stage B replaces the
 * body (ledger, reuse-before-create, create order, status reconcile) without changing the wiring.
 */
@Service
class RevolutBookingPaymentService : CardPaymentProvider {
    override val provider: CardProvider = CardProvider.REVOLUT

    override fun createCheckout(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        locale: String?,
    ): CheckoutSessionDto = throw CardPaymentsUnavailableException()

    override fun checkStatus(ref: String): CheckoutSessionDto? = null
}
