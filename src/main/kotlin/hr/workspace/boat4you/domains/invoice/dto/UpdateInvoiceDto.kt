package hr.workspace.boat4you.domains.invoice.dto

import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import java.math.BigDecimal

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
    val invoiceItem: String,
    val includeVat: Boolean,
    val vatPercentage: Float,
    val priceWithoutVat: BigDecimal,
    val vatAmount: BigDecimal,
    val totalPrice: BigDecimal,
)
