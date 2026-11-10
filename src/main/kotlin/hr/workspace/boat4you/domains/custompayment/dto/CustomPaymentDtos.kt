package hr.workspace.boat4you.domains.custompayment.dto

import hr.workspace.boat4you.domains.payment.mollie.KlarnaCountry
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import jakarta.validation.Valid
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.data.web.PagedModel
import java.math.BigDecimal
import java.time.Instant

/** GET /public/payments/custom/config: what the `/pay/<linkKey>` form needs. */
data class CustomPaymentConfigDto(
    /** false = Mollie is not configured; the page shows "temporarily unavailable". */
    val enabled: Boolean,
    val minAmountMinor: Long,
    val maxAmountMinor: Long,
    val currency: String,
    val termsVersion: String,
    /** Klarna offer for this page; null = not offered. */
    val klarna: KlarnaOfferDto? = null,
)

/** Where and up to which amount Klarna can be chosen (EUR billing countries; `/pay` and the booking payment step). */
data class KlarnaOfferDto(
    val countries: List<KlarnaCountry>,
    /** Largest limit among [countries], cents: above it Klarna is not shown at all. */
    val maxAmountMinor: Long,
    /** Booking only: surcharge for a Klarna payment in percent (the `/pay` link never adds one). */
    val surchargePercent: BigDecimal? = null,
)

/**
 * POST /public/payments/custom/orders. `amountMinor` = cents as an integer (a fraction is a 400 — a BigDecimal, so
 * Jackson cannot silently truncate 5000.5 to 5000). `reference` = the contract / booking confirmation number as free
 * text. The service re-checks everything (trim, pattern, configured min/max, locale of the site).
 */
data class CreateCustomPaymentRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val reference: String? = null,
    @field:NotNull
    @field:Min(5000)
    @field:Digits(integer = 9, fraction = 0)
    val amountMinor: BigDecimal? = null,
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String? = null,
    @field:NotNull
    @field:AssertTrue
    val acceptTerms: Boolean? = null,
    @field:Size(max = 5)
    val locale: String? = null,
    @field:NotBlank
    @field:Size(max = 32)
    val site: String? = null,
    @field:NotBlank
    @field:Size(max = 128)
    val linkKey: String? = null,
    /** Present = pay with Klarna (billing address required by Mollie); its `email` is ignored, [email] is used. */
    @field:Valid
    val klarna: KlarnaBillingRequest? = null,
)

/** Where to send the payer (Mollie hosted checkout) + our public token for the success page `?ref=`. */
data class CreateCustomPaymentResponse(
    val redirectUrl: String,
    val ref: String,
)

/** GET /public/payments/custom/orders/{token}: nothing else (no e-mail, no ids). */
data class CustomPaymentStatusDto(
    val status: CustomPaymentStatus,
    val amountMinor: Long,
    val currency: String,
    val reference: String,
    val site: String,
)

/** Admin list row. */
data class AdminCustomPaymentDto(
    val id: Long,
    val site: String,
    val createdAt: Instant,
    val completedAt: Instant?,
    val reference: String,
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val currency: String,
    val status: CustomPaymentStatus,
    /** `CARD` (cards, Apple Pay, Pay by Bank) or `KLARNA`. */
    val method: String,
    val payerEmail: String,
    val providerOrderId: String?,
    val adminNote: String?,
    val reservationId: Long?,
    val reservationNumber: String?,
)

/** Admin list: the page (same `content` / `page` shape as PagedModel) + money received among the filtered rows. */
data class CustomPaymentPageDto(
    val content: List<AdminCustomPaymentDto>,
    val page: PagedModel.PageMetadata,
    /** Cents: COMPLETED minus refunded, over the same filters (all pages). */
    val totalReceivedMinor: Long,
)

/** PATCH /admin/custom-payments/{id}/note — blank clears the note; at most 2000 characters. */
data class CustomPaymentNoteRequest(
    val note: String? = null,
)
