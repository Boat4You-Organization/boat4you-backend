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
        val reservationView = reservationViewRepository.findByReservationFlowId(invoice.reservationFlow.id!!)!!

        return InvoiceDto(
            id = invoice.id!!,
            reservationId = reservationView.reservationId!!,
            reservationNumber = reservationView.reservationNumber!!,
            reservationFlowId = invoice.reservationFlow.id!!,
            recipientType = invoice.recipientType,
            recipientName = invoice.recipientName,
            recipientCity = invoice.recipientCity,
            recipientStreet = invoice.recipientStreet,
            recipientZipCode = invoice.recipientZipCode,
            recipientCountry = invoice.recipientCountry,
            recipientVatCode = invoice.recipientVatCode,
            invoiceNumber = StringUtils.leftPad(invoice.invoiceNumber, 3, "0"),
            invoiceDate = invoice.invoiceDate,
            invoiceLanguage = invoice.invoiceLanguage,
            invoiceStatus = invoice.invoiceStatus,
            invoiceItem = invoice.invoiceItem,
            includeVat = invoice.includeVat!!,
            vatPercentage = invoice.vatPercentage!!,
            priceWithoutVat = invoice.priceWithoutVat,
            vatAmount = invoice.vatAmount,
            totalPrice = invoice.totalPrice,
            clientName = reservationView.agencyName ?: ("${reservationView.reservationFlowName} ${reservationView.reservationFlowSurname}"),
            clientEmail = reservationView.agencyEmail ?: reservationView.reservationFlowEmail,
            clientPhoneNumber = reservationView.agencyPhone ?: reservationView.reservationFlowPhone,
        )
    }
}
