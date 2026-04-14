package hr.workspace.boat4you.domains.invoice.controllers

import hr.workspace.boat4you.domains.invoice.dto.InvoiceDto
import hr.workspace.boat4you.domains.invoice.services.InvoiceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Invoices", description = "Invoice operations for general users")
@Validated
@RestController
@RequestMapping("/secured/invoices")
@PreAuthorize("isAuthenticated()")
internal class InvoiceController(
    private val invoiceService: InvoiceService,
) {
    @Operation(summary = "Get invoice details by ID")
    @GetMapping("/{id}")
    fun getInvoiceDetails(
        @PathVariable id: Long,
    ): ResponseEntity<InvoiceDto> {
        return ResponseEntity.ok(invoiceService.getByIdForOtherUsers(id))
    }

    @Operation(summary = "Get latest invoice details by reservation ID")
    @GetMapping
    fun getInvoiceDetailsForReservation(
        @RequestParam(name = "reservationId", required = true) reservationId: Long,
    ): ResponseEntity<InvoiceDto> {
        return ResponseEntity.ok(invoiceService.getByReservationIdForOtherUsers(reservationId))
    }
}
