package hr.workspace.boat4you.domains.custompayment.controllers

import hr.workspace.boat4you.domains.custompayment.dto.AdminCustomPaymentDto
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentNoteRequest
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentPageDto
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentAdminService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** "Custom payments" page of the admin panel: payments from the secret `/pay/<key>` links (record + note only). */
@RestController
@RequestMapping("/admin/custom-payments")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCustomPaymentController(
    private val adminService: CustomPaymentAdminService,
) {
    @Operation(summary = "List custom payments, newest first; optional status, site and q (reference / e-mail / Revolut order id)")
    @GetMapping
    fun list(
        @RequestParam(required = false) status: CustomPaymentStatus?,
        @RequestParam(required = false) site: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int,
    ): ResponseEntity<CustomPaymentPageDto> = ResponseEntity.ok(adminService.list(status, site, q, page, size))

    @Operation(summary = "Set the admin note of a payment ({\"note\": \"...\"}, at most 2000 characters; blank clears it)")
    @PatchMapping("/{id}/note")
    fun updateNote(
        @PathVariable id: Long,
        @RequestBody body: CustomPaymentNoteRequest,
    ): ResponseEntity<AdminCustomPaymentDto> = ResponseEntity.ok(adminService.updateNote(id, body.note))

    @Operation(summary = "Re-read the payment's Revolut order now and apply it (status, refunds)")
    @PostMapping("/{id}/refresh")
    fun refresh(
        @PathVariable id: Long,
    ): ResponseEntity<AdminCustomPaymentDto> = ResponseEntity.ok(adminService.refresh(id))
}
