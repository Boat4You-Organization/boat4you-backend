package hr.workspace.boat4you.domains.invoice.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "invoice")
class Invoice : AbstractEntity<Long>() {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "reservation_flow_id", nullable = false)
    lateinit var reservationFlow: ReservationFlow

    @Column(name = "recipient_type", columnDefinition = "VARCHAR(63)", nullable = false)
    @Enumerated(EnumType.STRING)
    lateinit var recipientType: InvoiceRecipientType

    @Column(name = "recipient_name", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var recipientName: String

    @Column(name = "recipient_city", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var recipientCity: String

    @Column(name = "recipient_street", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var recipientStreet: String

    @Column(name = "recipient_zip_code", columnDefinition = "VARCHAR(63)", nullable = false)
    lateinit var recipientZipCode: String

    @Column(name = "recipient_country", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var recipientCountry: String

    @Column(name = "recipient_vat_code", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var recipientVatCode: String

    @Column(name = "invoice_number", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var invoiceNumber: String

    @Column(name = "invoice_date", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var invoiceDate: LocalDate

    @Column(name = "invoice_language", columnDefinition = "VARCHAR(3)", nullable = false)
    @Enumerated(EnumType.STRING)
    lateinit var invoiceLanguage: InvoiceLanguageEnum

    @Column(name = "invoice_status", columnDefinition = "VARCHAR(63)", nullable = false)
    @Enumerated(EnumType.STRING)
    var invoiceStatus: InvoiceStatus = InvoiceStatus.DRAFT

    @Column(name = "invoice_item", columnDefinition = "VARCHAR(1023)", nullable = false)
    lateinit var invoiceItem: String

    @Column(name = "include_vat", columnDefinition = "BOOLEAN", nullable = false)
    var includeVat: Boolean? = null

    @Column(name = "vat_percentage", columnDefinition = "REAL", nullable = false)
    var vatPercentage: Float? = null

    @Column(name = "price_without_vat", columnDefinition = "DECIMAL", nullable = false)
    lateinit var priceWithoutVat: BigDecimal

    @Column(name = "vat_amount", columnDefinition = "DECIMAL", nullable = false)
    lateinit var vatAmount: BigDecimal

    @Column(name = "total_price", columnDefinition = "DECIMAL", nullable = false)
    lateinit var totalPrice: BigDecimal
}
