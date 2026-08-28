package hr.workspace.boat4you.domains.invoice.dto

import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import java.math.BigDecimal
import java.time.LocalDate

data class UpdateInvoiceDto(
    val recipientType: InvoiceRecipientType,
    val recipientName: String,
    val recipientCity: String,
    val recipientStreet: String,
    val recipientZipCode: String,
    val recipientCountry: CountryIsoEnum,
    val recipientVatCode: String,
    val invoiceLanguage: InvoiceLanguageEnum,
    val invoiceStatus: InvoiceStatus? = null,
    /** Editable since 22.8.2026 (Mario) — blank/absent keeps the generated number. */
    val invoiceNumber: String? = null,
    /** Paper contract / booking-confirmation number; absent keeps the stored
     *  value, explicit blank clears it. */
    val contractNumber: String? = null,
    val invoiceItem: String,
    /** Absent keeps the stored dates (same guard as invoiceNumber/contractNumber). */
    val charterDateFrom: LocalDate? = null,
    val charterDateTo: LocalDate? = null,
    val includeVat: Boolean,
    val vatPercentage: Float,
    val priceWithoutVat: BigDecimal,
    val vatAmount: BigDecimal,
    val totalPrice: BigDecimal,
)
