package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.payment.mollie.KlarnaBilling
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Optional JSON body of `POST …/payments/card/create-checkout-session/{reservationId}`. Absent or without [klarna] =
 * the card checkout as before. With [klarna] the hosted checkout offers Klarna only; the billing data is personal data,
 * which is why it travels in the body and never in the query string.
 */
data class CardCheckoutRequest(
    @field:Valid
    val klarna: KlarnaBillingRequest? = null,
)

/** Billing address of the payer, as Mollie requires it for Klarna (also the `/pay` form). */
data class KlarnaBillingRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val givenName: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val familyName: String? = null,
    /** Blank = the e-mail of the booking (the `/pay` form sends its own e-mail field instead). */
    @field:Email
    @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val streetAndNumber: String? = null,
    @field:NotBlank
    @field:Size(max = 20)
    val postalCode: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val city: String? = null,
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Za-z]{2}$")
    val country: String? = null,
) {
    /** Trimmed, control characters removed, country upper case. [fallbackEmail] when the form sent none. */
    fun toBilling(fallbackEmail: String?): KlarnaBilling =
        KlarnaBilling(
            givenName = clean(givenName),
            familyName = clean(familyName),
            email = clean(email).ifEmpty { clean(fallbackEmail) },
            streetAndNumber = clean(streetAndNumber),
            postalCode = clean(postalCode),
            city = clean(city),
            country = clean(country).uppercase(),
        )

    private fun clean(value: String?): String = value.orEmpty().replace(CONTROL_CHARS, " ").trim()

    private companion object {
        val CONTROL_CHARS = Regex("\\p{Cntrl}+")
    }
}
