package hr.workspace.boat4you.domains.reservation.service.payment

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Picks the card provider of the online-booking `/payments/card` endpoints.
 * - New checkout: the provider named by `application.payment.card-provider` (`stripe` | `mollie`, default `stripe`;
 *   blank = `stripe`, any other value logs an ERROR and stays on `stripe`). `mollie` while Mollie is not configured on
 *   this node: ERROR at startup and 503 per request — the boot never fails because of it.
 * - Status: Mollie when it knows the reference (its own ledger, independent of the flag, so payments created before
 *   a rollback to `stripe` still report), else Stripe, else 404.
 *
 * The flag is read once at startup; switching provider = change `PAYMENT_CARD_PROVIDER` + restart.
 */
@Service
class CardPaymentRouter(
    providerBeans: List<CardPaymentProvider>,
    private val mollieProperties: MollieProperties,
    bookingProperties: MollieBookingProperties,
    @Value("\${application.payment.card-provider:stripe}") configuredProvider: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providers: Map<CardProvider, CardPaymentProvider> = providerBeans.associateBy { it.provider }

    /** Provider of every NEW checkout. */
    val activeProvider: CardProvider = CardProvider.fromConfig(configuredProvider) ?: CardProvider.STRIPE

    init {
        check(providers.size == providerBeans.size) { "More than one CardPaymentProvider per provider: ${providerBeans.map { it.provider }}" }
        if (configuredProvider.isNotBlank() && CardProvider.fromConfig(configuredProvider) == null) {
            log.error(
                "application.payment.card-provider '{}' is not stripe|mollie — card checkouts stay on stripe",
                configuredProvider.take(MAX_LOGGED_VALUE).replace(UNSAFE_LOG_CHARS, "?"),
            )
        }
        if (mollieSelectedButDisabled()) {
            log.error(
                "application.payment.card-provider=mollie but Mollie is not configured on this node — " +
                    "every card checkout answers 503 until it is (or the provider is set back to stripe)",
            )
        }
        log.info(
            "Card payments: provider={}, mollie-enabled={}, mollie checkout-ttl={}, card-max={} cents",
            activeProvider.name.lowercase(),
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
    ): CheckoutSessionDto {
        if (mollieSelectedButDisabled()) throw CardPaymentsUnavailableException()
        val provider = providers[activeProvider] ?: throw CardPaymentsUnavailableException()
        return provider.createCheckout(reservationId, payFullAmount, paymentPhaseId, idempotencyKey, locale)
    }

    fun checkStatus(ref: String): CheckoutSessionDto {
        if (ref.isBlank() || ref.length > MAX_REF_LENGTH) throw CardPaymentNotFoundException()
        return providers[CardProvider.MOLLIE]?.checkStatus(ref)
            ?: providers[CardProvider.STRIPE]?.checkStatus(ref)
            ?: throw CardPaymentNotFoundException()
    }

    private fun mollieSelectedButDisabled(): Boolean = activeProvider == CardProvider.MOLLIE && !mollieProperties.enabled

    private companion object {
        /** Stripe session ids (`cs_…`), Mollie ids and our tokens are far shorter; anything longer is unknown by definition. */
        const val MAX_REF_LENGTH = 255
        const val MAX_LOGGED_VALUE = 32
        val UNSAFE_LOG_CHARS = Regex("[^A-Za-z0-9_.-]")
    }
}
