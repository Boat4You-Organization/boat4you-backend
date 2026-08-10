package hr.workspace.boat4you.domains.voucher.controllers

import hr.workspace.boat4you.domains.voucher.dto.VoucherValidationDto
import hr.workspace.boat4you.domains.voucher.service.VoucherService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * Public because the voucher is transferable — the code itself is the
 * credential (guest checkout has no auth cookie at all). Read-only preview;
 * the atomic claim happens inside reservation creation.
 */
@Tag(name = "Vouchers", description = "Loyalty voucher validation")
@RestController
@RequestMapping("/public/vouchers")
class PublicVoucherController(
    private val voucherService: VoucherService,
) {
    @Operation(summary = "Validate a voucher code before checkout (no redemption).")
    @GetMapping("/validate")
    fun validate(
        @Parameter(description = "Voucher code, e.g. B4Y-XXXX-XXXX") @RequestParam(name = "code") code: String,
        @Parameter(description = "Client total in EUR — checks the minimum-spend rule when given") @RequestParam(
            name = "total",
            required = false,
        ) total: BigDecimal? = null,
    ): ResponseEntity<VoucherValidationDto> = ResponseEntity.ok(voucherService.validate(code, total))
}
