package hr.workspace.boat4you.domains.campaign.controllers

import hr.workspace.boat4you.domains.campaign.services.CampaignService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Public (unauthenticated — the /public/ prefix is permitAll) unsubscribe endpoint.
 * GET serves humans clicking the footer link (302 → friendly web page);
 * POST serves RFC 8058 one-click unsubscribe that Gmail/Yahoo fire from the
 * `List-Unsubscribe` header. Both are idempotent and answer identically for
 * unknown tokens so token validity can't be probed.
 */
@Tag(name = "Newsletter", description = "Public newsletter endpoints")
@RestController
@RequestMapping("/public/newsletter")
class PublicNewsletterController(
    private val campaignService: CampaignService,
    @Value("\${application.campaign.unsubscribed-page-url:https://www.boat4you.com/unsubscribed}")
    private val unsubscribedPageUrl: String,
) {
    @Operation(summary = "Unsubscribe via footer link (redirects to a confirmation page)")
    @GetMapping("/unsubscribe")
    fun unsubscribe(
        @RequestParam token: String,
    ): ResponseEntity<Unit> {
        campaignService.unsubscribeByToken(token)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, unsubscribedPageUrl)
            .build()
    }

    @Operation(summary = "RFC 8058 one-click unsubscribe (mail providers POST here)")
    @PostMapping("/unsubscribe")
    fun unsubscribeOneClick(
        @RequestParam token: String,
    ): ResponseEntity<String> {
        campaignService.unsubscribeByToken(token)
        return ResponseEntity.ok("You have been unsubscribed.")
    }
}
