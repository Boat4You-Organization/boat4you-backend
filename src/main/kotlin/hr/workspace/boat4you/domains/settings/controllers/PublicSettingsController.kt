package hr.workspace.boat4you.domains.settings.controllers

import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * Read-only public exposure of a narrow slice of system settings that the
 * web frontend needs BEFORE the user authenticates (e.g. so the pricing UI
 * can show the +% card surcharge at payment-method selection time).
 *
 * Only safe, non-sensitive settings go here. Every full-settings read stays
 * on [AdminSettingsController].
 */
@Tag(name = "Public Settings", description = "Public read of selected settings visible to unauthenticated users")
@RestController
@RequestMapping("/public/settings")
class PublicSettingsController(
    private val settingsService: AdminSettingsService,
) {
    /**
     * Returns the configured card-payment surcharge as a percentage (e.g. "5.00").
     * Falls back to "0" if unset. Never returns null so the frontend can always
     * parse a number.
     */
    @Operation(summary = "Get card payment surcharge percentage (public, safe to expose)")
    @GetMapping("/card-surcharge")
    fun getCardPaymentSurcharge(): ResponseEntity<CardSurchargeDto> {
        val setting = settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE)
        val percentage = setting.value?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return ResponseEntity.ok(CardSurchargeDto(percentage = percentage.toPlainString()))
    }
}

/** Small DTO kept local — this value is only read from the public endpoint. */
data class CardSurchargeDto(val percentage: String)
