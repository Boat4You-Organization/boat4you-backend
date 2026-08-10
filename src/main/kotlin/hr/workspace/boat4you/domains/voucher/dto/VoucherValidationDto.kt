package hr.workspace.boat4you.domains.voucher.dto

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pre-redemption preview for the checkout voucher field. `reason` is set only
 * when invalid: NOT_FOUND / NOT_ACTIVE / EXPIRED / MIN_TOTAL_NOT_MET.
 */
data class VoucherValidationDto(
    val valid: Boolean,
    val value: BigDecimal? = null,
    val currency: String? = null,
    val validTo: LocalDate? = null,
    val reason: String? = null,
)
