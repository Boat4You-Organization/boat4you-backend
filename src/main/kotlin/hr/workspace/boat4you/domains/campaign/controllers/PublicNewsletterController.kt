package hr.workspace.boat4you.domains.campaign.controllers

import hr.workspace.boat4you.domains.campaign.services.CampaignService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
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
) {
    @Operation(summary = "Unsubscribe via footer link (serves a confirmation page)")
    @GetMapping("/unsubscribe", produces = [MediaType.TEXT_HTML_VALUE])
    fun unsubscribe(
        @RequestParam token: String,
    ): ResponseEntity<String> {
        campaignService.unsubscribeByToken(token)
        // Self-contained confirmation page — no dependency on a web-app
        // route existing; identical response for unknown tokens by design.
        val page = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Unsubscribed — Boat4you</title></head>
            <body style="margin:0;background:#f4f6fb;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;color:#0f172a;">
            <div style="max-width:520px;margin:80px auto;padding:40px;background:#fff;border-radius:14px;text-align:center;box-shadow:0 1px 3px rgba(15,23,42,.08);">
            <h1 style="font-size:22px;margin:0 0 12px;">You're unsubscribed ✔</h1>
            <p style="font-size:15px;line-height:1.6;color:#475569;margin:0 0 20px;">You won't receive further marketing emails from Boat4you · Europe Yachts Charter. Booking-related emails for your existing reservations are not affected.</p>
            <a href="https://www.boat4you.com" style="color:#2856ff;font-weight:600;text-decoration:none;">boat4you.com</a>
            </div></body></html>
        """.trimIndent()
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page)
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
