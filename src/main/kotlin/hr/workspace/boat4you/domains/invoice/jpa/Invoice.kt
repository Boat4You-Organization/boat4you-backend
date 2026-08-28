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
    // Nullable since manual invoices (V9_48): admin-issued invoices for
    // agencies/clients don't have to reference a reservation.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "reservation_flow_id", nullable = true)
    var reservationFlow: ReservationFlow? = null

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

    // Mario's paper contract / booking-confirmation number (V9_49) — the key
    // he files charters under. Auto-generated invoices carry the reservation
    // number here; manual ones whatever the admin typed.
    @Column(name = "contract_number", columnDefinition = "VARCHAR(63)", nullable = true)
    var contractNumber: String? = null

    /**
     * Numeric-friendly sort key for the Booking column: the digits before the
     * "/" left-padded to 12, so 100198/2026 < 1001089/2026 sorts numerically
     * instead of lexicographically (contract numbers mix 6- and 7-digit
     * forms straight off Mario's paper contracts). Read-only DB formula —
     * the listing sorts on this when the admin clicks the Booking header.
     */
    @org.hibernate.annotations.Formula("lpad(coalesce(split_part(contract_number, '/', 1), ''), 12, '0')")
    var contractNumberSort: String? = null

    @Column(name = "charter_date_from", columnDefinition = "DATE")
    var charterDateFrom: LocalDate? = null

    @Column(name = "charter_date_to", columnDefinition = "DATE")
    var charterDateTo: LocalDate? = null

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
