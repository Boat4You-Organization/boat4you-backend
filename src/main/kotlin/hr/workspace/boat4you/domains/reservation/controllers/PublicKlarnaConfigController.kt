package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.custompayment.dto.KlarnaOfferDto
import hr.workspace.boat4you.domains.payment.mollie.MollieKlarnaProperties
import hr.workspace.boat4you.domains.reservation.service.CardChargeCalculator
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * What the booking payment step needs to offer Klarna: the billing countries with their limits and the surcharge.
 * 204 = Klarna is not offered (switched off, or Mollie is not configured). No secrets, nothing personal.
 */
@Tag(name = "Payments (public)")
@RestController
@RequestMapping("/public/payments/card")
class PublicKlarnaConfigController(
    private val klarnaProperties: MollieKlarnaProperties,
    private val settingsService: AdminSettingsService,
) {
    @Operation(summary = "Klarna offer of the booking payment step (204 when Klarna is not offered)")
    @GetMapping("/klarna")
    fun klarna(): ResponseEntity<KlarnaOfferDto> {
        if (!klarnaProperties.enabled) return ResponseEntity.noContent().build()
        val surcharge =
            klarnaProperties.surchargePercentOverride
                ?: CardChargeCalculator.surchargePercentage(settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value)
        return ResponseEntity.ok(KlarnaOfferDto(klarnaProperties.countries, klarnaProperties.maxAmountMinor, surcharge))
    }
}
