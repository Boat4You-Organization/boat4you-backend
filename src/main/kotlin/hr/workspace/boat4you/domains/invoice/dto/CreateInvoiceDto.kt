package hr.workspace.boat4you.domains.invoice.dto

import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Manual invoice creation (admin). Mirrors [UpdateInvoiceDto] plus the fields
 * an update never touches: `invoiceDate` and the OPTIONAL `reservationId` link
 * — a manual invoice may stand alone (agency service bill) or be tied to a
 * booking for traceability.
 */
data class CreateInvoiceDto(
    /** Optional booking link; null = standalone invoice. */
    val reservationId: Long? = null,
    val recipientType: InvoiceRecipientType,
    val recipientName: String,
    val recipientCity: String,
    val recipientStreet: String,
    val recipientZipCode: String,
    val recipientCountry: CountryIsoEnum,
    val recipientVatCode: String,
    val invoiceDate: LocalDate,
    val invoiceLanguage: InvoiceLanguageEnum,
    /** Blank/absent → next number in the yearly `NNNNNN/GGGG` sequence. */
    val invoiceNumber: String? = null,
    /** Paper contract / booking-confirmation number the invoice belongs to. */
    val contractNumber: String? = null,
    val invoiceItem: String,
    /** Charter departure / return; both optional (standalone invoices may omit). */
    val charterDateFrom: LocalDate? = null,
    val charterDateTo: LocalDate? = null,
    val charterCountry: String? = null,
    val includeVat: Boolean,
    val vatPercentage: Float,
    val priceWithoutVat: BigDecimal,
    val vatAmount: BigDecimal,
    val totalPrice: BigDecimal,
)
