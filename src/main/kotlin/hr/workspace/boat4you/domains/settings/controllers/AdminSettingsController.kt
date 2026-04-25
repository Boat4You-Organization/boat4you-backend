package hr.workspace.boat4you.domains.settings.controllers

import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Settings", description = "System Settings operations for administrators")
@RestController
@RequestMapping("/admin/settings")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminSettingsController(
    private val settingsService: AdminSettingsService,
) {
    @GetMapping
    fun getAllSettings(): ResponseEntity<List<SettingsDto>> {
        return ResponseEntity.ok(
            settingsService.getAllSettings(),
        )
    }

    @PatchMapping
    @Operation(
        summary = "Update setting",
        description =
            "When updating CARD_PAYMENT_SURCHARGE, percentage is sent, e.g. '2.5'. " +
                "When updating BANK_TRANSFER_FIXED_FEE, a non-negative flat EUR amount is sent, e.g. '32.00'.",
    )
    fun updateSetting(
        @RequestBody setting: SettingsDto,
    ): ResponseEntity<SettingsDto> {
        return ResponseEntity.ok(settingsService.updateSetting(setting))
    }
}
