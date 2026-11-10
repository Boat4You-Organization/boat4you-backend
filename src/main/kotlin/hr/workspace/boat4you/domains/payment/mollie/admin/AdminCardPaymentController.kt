package hr.workspace.boat4you.domains.payment.mollie.admin

import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * "Payments" page of the admin panel and the "Card payments" block of a booking: the Mollie payments of online
 * bookings (list + Refresh) and the live Mollie details of any of our payments. Nothing here moves money.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCardPaymentController(
    private val adminService: AdminCardPaymentService,
) {
    @Operation(
        summary =
            "List booking card payments, newest first; optional status (comma-separated), reservationId and " +
                "q (booking number / client name or e-mail / Mollie payment id)",
    )
    @GetMapping("/card-payments")
    fun list(
        @RequestParam(required = false) status: List<ReservationCardOrderStatus>?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) reservationId: Long?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int,
    ): ResponseEntity<AdminCardPaymentPageDto> = ResponseEntity.ok(adminService.list(status, q, reservationId, page, size))

    @Operation(summary = "Re-read the booking payment's Mollie payment now and apply it (status, refunds, chargebacks)")
    @PostMapping("/card-payments/{id}/refresh")
    fun refresh(
        @PathVariable id: Long,
    ): ResponseEntity<AdminCardPaymentDto> = ResponseEntity.ok(adminService.refresh(id))

    @Operation(summary = "One of our Mollie payments as Mollie reports it now, with its refunds and chargebacks")
    @GetMapping("/mollie-payments/{paymentId}")
    fun mollieDetails(
        @PathVariable paymentId: String,
    ): ResponseEntity<AdminMolliePaymentDto> = ResponseEntity.ok(adminService.mollieDetails(paymentId))
}
