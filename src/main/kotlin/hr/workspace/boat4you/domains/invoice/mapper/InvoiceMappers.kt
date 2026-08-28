package hr.workspace.boat4you.domains.invoice.mapper

import hr.workspace.boat4you.domains.invoice.dto.InvoiceDto
import hr.workspace.boat4you.domains.invoice.jpa.Invoice
import hr.workspace.boat4you.domains.reservation.jpa.ReservationViewRepository
import org.apache.commons.lang3.StringUtils
import org.springframework.stereotype.Component

@Component
class InvoiceMappers(
    private val reservationViewRepository: ReservationViewRepository,
) {
    fun toInvoiceDto(invoice: Invoice): InvoiceDto {
        // Manual invoices have no reservation — every booking-derived field
        // below then falls back to the invoice's own recipient data.
        val reservationView = invoice.reservationFlow?.id?.let { reservationViewRepository.findByReservationFlowId(it) }

        return InvoiceDto(
            id = invoice.id!!,
            reservationId = reservationView?.reservationId,
            reservationNumber = reservationView?.reservationNumber,
            reservationFlowId = invoice.reservationFlow?.id,
            recipientType = invoice.recipientType,
            recipientName = invoice.recipientName,
            recipientCity = invoice.recipientCity,
            recipientStreet = invoice.recipientStreet,
            recipientZipCode = invoice.recipientZipCode,
            recipientCountry = invoice.recipientCountry,
            recipientVatCode = invoice.recipientVatCode,
            // Yearly-scheme numbers ("100101/2026") pass through verbatim;
            // legacy plain integers keep the historical 3-digit padding.
            invoiceNumber =
                if (invoice.invoiceNumber.contains('/')) {
                    invoice.invoiceNumber
                } else {
                    StringUtils.leftPad(invoice.invoiceNumber, 3, "0")
                },
            contractNumber = invoice.contractNumber,
            invoiceDate = invoice.invoiceDate,
            invoiceLanguage = invoice.invoiceLanguage,
            invoiceStatus = invoice.invoiceStatus,
            invoiceItem = invoice.invoiceItem,
            includeVat = invoice.includeVat!!,
            vatPercentage = invoice.vatPercentage!!,
            priceWithoutVat = invoice.priceWithoutVat,
            vatAmount = invoice.vatAmount,
            totalPrice = invoice.totalPrice,
            reservationCommission = reservationView?.reservationCommission,
            clientName = reservationView?.let { it.agencyName ?: "${it.reservationFlowName} ${it.reservationFlowSurname}" }
                ?: invoice.recipientName,
            clientEmail = reservationView?.let { it.agencyEmail ?: it.reservationFlowEmail },
            clientPhoneNumber = reservationView?.let { it.agencyPhone ?: it.reservationFlowPhone },
        )
    }
}
