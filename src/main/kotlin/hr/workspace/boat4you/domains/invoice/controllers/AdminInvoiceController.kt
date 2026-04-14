package hr.workspace.boat4you.domains.invoice.controllers

import hr.workspace.boat4you.domains.invoice.dto.InvoiceDto
import hr.workspace.boat4you.domains.invoice.dto.UpdateInvoiceDto
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import hr.workspace.boat4you.domains.invoice.services.InvoiceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.openapitools.model.LanguageEnum
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Invoice Management", description = "Manage Invoices for Administrators")
@Validated
@RestController
@RequestMapping("/admin/invoices")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminInvoiceController(
    private val invoiceService: InvoiceService,
) {
    @Operation(summary = "Get all invoices")
    @GetMapping
    fun getInvoices(
        @RequestParam(name = "reservationId", required = false) reservationId: Long? = null,
        @RequestParam(name = "recipientType", required = false) recipientType: InvoiceRecipientType? = null,
        @RequestParam(name = "recipientName", required = false) recipientName: String? = null,
        @RequestParam(name = "language", required = false) language: LanguageEnum? = null,
        @RequestParam(name = "departureDate", required = false) departureDate: LocalDate? = null,
        @RequestParam(name = "agencyId", required = false) agencyId: Long? = null,
        @RequestParam(name = "invoiceStatus", required = false) invoiceStatus: InvoiceStatus? = null,
        @PageableDefault(
            sort = ["created"],
            direction = Sort.Direction.DESC,
        ) pageable: Pageable,
    ): ResponseEntity<PagedModel<InvoiceDto>> {
        val invoices = invoiceService.getAllForAdmin(reservationId, recipientType, recipientName, language, departureDate, agencyId, invoiceStatus, pageable)
        return ResponseEntity.ok(PagedModel(invoices))
    }

    @Operation(summary = "Get invoice details by ID")
    @GetMapping("/{id}")
    fun getInvoiceDetails(
        @PathVariable id: Long,
    ): ResponseEntity<InvoiceDto> {
        val invoice = invoiceService.getByIdForAdmin(id)
        return if (invoice == null) {
            ResponseEntity(HttpStatus.NOT_FOUND)
        } else {
            ResponseEntity.ok(invoice)
        }
    }

    @Operation(description = "Update invoice")
    @PutMapping("/{id}")
    fun updateInvoice(
        @PathVariable id: Long,
        @RequestBody model: UpdateInvoiceDto,
    ): ResponseEntity<InvoiceDto> {
        return ResponseEntity.ok(invoiceService.updateInvoice(id, model))
    }

    @Operation(description = "Mark invoice as sent")
    @PutMapping("/{id}/markAsSent")
    fun markAsSent(
        @PathVariable id: Long,
    ): ResponseEntity<InvoiceDto> {
        return ResponseEntity.ok(invoiceService.markInvoiceAsSent(id))
    }

    @Operation(description = "Delete invoice")
    @DeleteMapping("/{id}")
    fun deleteInvoice(
        @PathVariable id: Long,
    ): ResponseEntity<Unit> {
        invoiceService.deleteInvoice(id)
        return ResponseEntity(HttpStatus.OK)
    }
}
