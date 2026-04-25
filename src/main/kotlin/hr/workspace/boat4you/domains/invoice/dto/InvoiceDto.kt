package hr.workspace.boat4you.domains.invoice.dto

import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import java.math.BigDecimal
import java.time.LocalDate

data class InvoiceDto(
    val id: Long,
    val reservationId: Long,
    val reservationNumber: String,
    val reservationFlowId: Long,
    val recipientType: InvoiceRecipientType,
    val recipientName: String,
    val recipientCity: String,
    val recipientStreet: String,
    val recipientZipCode: String,
    val recipientCountry: String,
    val recipientVatCode: String,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val invoiceLanguage: InvoiceLanguageEnum,
    val invoiceStatus: InvoiceStatus,
    val invoiceItem: String,
    val includeVat: Boolean,
    val vatPercentage: Float,
    val priceWithoutVat: BigDecimal,
    val vatAmount: BigDecimal,
    val totalPrice: BigDecimal,
    /**
     * Broker commission on the related reservation, surfaced for the
     * invoice listing's "Amount" column. The invoice itself bills the
     * broker fee — listing reads this directly so the figure stays in
     * sync with the booking's commission even when the user hasn't yet
     * filled `priceWithoutVat` / `totalPrice` on the invoice draft.
     */
    val reservationCommission: BigDecimal? = null,
    val clientName: String,
    val clientEmail: String? = null,
    val clientPhoneNumber: String? = null,
)
