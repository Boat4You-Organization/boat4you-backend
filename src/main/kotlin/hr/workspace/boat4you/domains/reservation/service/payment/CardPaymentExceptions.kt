package hr.workspace.boat4you.domains.reservation.service.payment

/*
 * Online-booking card payments (`/payments/card`). Each maps to ONE static, customer-facing text in ApiErrorCodes
 * (81xx) via ApiErrorHandler — never a provider message.
 */

/** The configured provider cannot take payments on this node (e.g. `revolut` without Merchant API keys) -> 503, 8101. */
class CardPaymentsUnavailableException : RuntimeException()

/** A card payment for the same phases is already being processed by the provider -> 409, 8102. */
class CardPaymentInProgressException : RuntimeException()

/** The phases of this checkout are already paid (e.g. a completed order found while creating a new one) -> 409, 8103. */
class CardAlreadyPaidException : RuntimeException()

/** The provider refused or did not answer while creating / reading a checkout -> 502, 8104. */
class CardPaymentProviderException : RuntimeException()

/** No provider knows the checkout reference of a status request -> 404, 8105. */
class CardPaymentNotFoundException : RuntimeException()
