package hr.workspace.boat4you.domains.payment.mollie

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * What Mollie needs about the payer for a Klarna payment (`billingAddress`; all fields required by Mollie). It only
 * travels to Mollie / Klarna: nothing of it is stored on our side.
 */
data class KlarnaBilling(
    val givenName: String,
    val familyName: String,
    val email: String,
    val streetAndNumber: String,
    val postalCode: String,
    val city: String,
    /** ISO 3166-1 alpha-2, upper case, one of [MollieKlarnaProperties.countries]. */
    val country: String,
) {
    fun toMollie() = MollieBillingAddress(givenName, familyName, email, streetAndNumber, postalCode, city, country)
}

/** One billing country Klarna accepts for a EUR payment, with its Klarna limit in cents. */
data class KlarnaCountry(
    val code: String,
    val maxAmountMinor: Long,
)

/** The Klarna offer is refused: disabled, billing country not supported, or the amount above that country's limit -> 422, code 8107. */
class KlarnaNotAvailableException(
    val reason: String,
) : RuntimeException(reason)

/**
 * `application.mollie.klarna.*` — Klarna through Mollie (Mario 10.11.2026). We charge in EUR only, so Klarna is offered
 * to payers whose BILLING country is a euro country Klarna serves (Mollie docs "Klarna", 10.11.2026; a test-mode
 * payment with billing country HR is refused by Mollie: "The billing country is not supported"). GB, CH, the Nordics
 * and PL / CZ / RO / HU need their own currency; US, AU and CA are not served at all. Every limit is also capped by the
 * account's own Klarna maximum (Methods API: 10 000 EUR). Off unless `enabled` is set AND Mollie itself is enabled.
 */
@Component
class MollieKlarnaProperties(
    @Value("\${application.mollie.klarna.enabled:false}") enabledFlag: Boolean,
    @Value("\${application.mollie.klarna.max-amount:10000}") maxAmount: Long,
    @Value("\${application.mollie.klarna.surcharge-percent:}") surchargePercent: String,
    private val mollie: MollieProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val enabled: Boolean = enabledFlag && mollie.enabled

    /**
     * Surcharge for a Klarna payment of a booking, in percent. Blank = the card surcharge of the admin settings (one
     * price for every Mollie method). A value that is not a number from 0 to 100 never fails the boot: ERROR + blank.
     */
    val surchargePercentOverride: BigDecimal? =
        surchargePercent.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= HUNDRED }
                ?: null.also { log.error("application.mollie.klarna.surcharge-percent '{}' is not a percentage — using the card surcharge", raw) }
        }

    private val accountMaxMinor = maxAmount.coerceAtLeast(0) * CENTS_PER_EUR

    /** Billing countries offered, with the limit that applies to this account. */
    val countries: List<KlarnaCountry> = EUR_LIMITS.map { (code, eur) -> KlarnaCountry(code, minOf(eur * CENTS_PER_EUR, accountMaxMinor)) }

    private val byCode = countries.associateBy { it.code }

    /** The largest amount any billing country can pay with Klarna (the web hides Klarna above it). */
    val maxAmountMinor: Long = countries.maxOfOrNull { it.maxAmountMinor } ?: 0

    /** Throws [KlarnaNotAvailableException] unless a Klarna payment of [amountMinor] cents for [billing] can be created. */
    fun requireAvailable(
        billing: KlarnaBilling,
        amountMinor: Long,
    ) {
        if (!enabled) throw KlarnaNotAvailableException("disabled")
        val country = byCode[billing.country] ?: throw KlarnaNotAvailableException("country")
        if (amountMinor > country.maxAmountMinor) throw KlarnaNotAvailableException("amount")
    }

    init {
        log.info("Klarna: enabled={}, countries={}, account max={} EUR, surcharge={}", enabled, countries.size, maxAmount, surchargePercentOverride ?: "as card")
    }

    companion object {
        private const val CENTS_PER_EUR = 100L
        private val HUNDRED = BigDecimal(100)

        /** Klarna's EUR limits per billing country, whole EUR (Mollie docs "Klarna", read 10.11.2026). */
        val EUR_LIMITS: Map<String, Long> =
            linkedMapOf(
                "AT" to 10_000L,
                "BE" to 10_000L,
                "DE" to 10_000L,
                "FI" to 10_000L,
                "FR" to 10_000L,
                "NL" to 14_000L,
                "ES" to 4_000L,
                "GR" to 4_000L,
                "IE" to 4_000L,
                "IT" to 4_000L,
                "PT" to 4_000L,
                "SK" to 4_000L,
            )
    }
}
