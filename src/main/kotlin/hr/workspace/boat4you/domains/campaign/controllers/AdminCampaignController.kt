package hr.workspace.boat4you.domains.campaign.controllers

import hr.workspace.boat4you.domains.campaign.jpa.CampaignRecipientStatus
import hr.workspace.boat4you.domains.campaign.services.CampaignService
import hr.workspace.boat4you.domains.campaign.services.ImportResult
import hr.workspace.boat4you.domains.campaign.services.RecipientImport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class BulkRecipientsDto(
    val campaign: String,
    val recipients: List<RecipientImport>,
)

data class BulkSuppressionDto(
    val emails: List<String>,
    /** e.g. GDPR_ERASURE, MANUAL, BOUNCE */
    val reason: String,
)

@Tag(name = "Campaign Management", description = "Email campaign queue & do-not-mail list")
@RestController
@RequestMapping("/admin/campaign")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCampaignController(
    private val campaignService: CampaignService,
) {
    @Operation(summary = "Queue recipients for a campaign (idempotent per campaign+email)")
    @PostMapping("/recipients/bulk")
    fun importRecipients(
        @RequestBody body: BulkRecipientsDto,
    ): ResponseEntity<ImportResult> = ResponseEntity.ok(campaignService.bulkImport(body.campaign, body.recipients))

    @Operation(summary = "Add addresses to the permanent do-not-mail list")
    @PostMapping("/suppression/bulk")
    fun importSuppression(
        @RequestBody body: BulkSuppressionDto,
    ): ResponseEntity<Map<String, Int>> = ResponseEntity.ok(mapOf("added" to campaignService.bulkSuppress(body.emails, body.reason)))

    @Operation(summary = "Send-queue status counts for a campaign")
    @GetMapping("/{campaign}/stats")
    fun stats(
        @PathVariable campaign: String,
    ): ResponseEntity<Map<CampaignRecipientStatus, Long>> = ResponseEntity.ok(campaignService.stats(campaign))
}
