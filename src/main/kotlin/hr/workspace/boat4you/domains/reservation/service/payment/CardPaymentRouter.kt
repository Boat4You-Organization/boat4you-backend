package hr.workspace.boat4you.domains.reservation.service.payment

import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingProperties
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The online-booking `/payments/card` endpoints in front of the card provider (Mollie).
 * - New checkout: 503 while Mollie is not configured on this node (ERROR at startup; the boot never fails because of
 *   it).
 * - Status: the provider's own ledger; a reference it does not know is a 404.
 */
@Service
class CardPaymentRouter(
    private val provider: CardPaymentProvider,
    private val mollieProperties: MollieProperties,
    bookingProperties: MollieBookingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (!mollieProperties.enabled) {
            log.error("Card payments: Mollie is not configured on this node — every card checkout answers 503 until it is")
        }
        log.info(
            "Card payments: provider=mollie, mollie-enabled={}, mollie checkout-ttl={}, card-max={} cents",
            mollieProperties.enabled,
            bookingProperties.checkoutTtl,
            mollieProperties.cardMaxAmountMinor,
        )
    }

    fun createCheckout(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String?,
        locale: String?,
        klarna: KlarnaBillingRequest? = null,
    ): CheckoutSessionDto {
        if (!mollieProperties.enabled) throw CardPaymentsUnavailableException()
        return provider.createCheckout(reservationId, payFullAmount, paymentPhaseId, idempotencyKey, locale, klarna)
    }

    fun checkStatus(ref: String): CheckoutSessionDto {
        if (ref.isBlank() || ref.length > MAX_REF_LENGTH) throw CardPaymentNotFoundException()
        return provider.checkStatus(ref) ?: throw CardPaymentNotFoundException()
    }

    private companion object {
        /** Mollie ids and our checkout tokens are far shorter; anything longer is unknown by definition. */
        const val MAX_REF_LENGTH = 255
    }
}
