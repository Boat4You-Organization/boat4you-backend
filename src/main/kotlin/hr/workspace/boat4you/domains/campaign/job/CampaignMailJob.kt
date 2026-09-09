package hr.workspace.boat4you.domains.campaign.job

import hr.workspace.boat4you.domains.campaign.services.CampaignService
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.Resource
import org.springframework.mail.MailException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drip-sender for email campaigns (Early Booking 2027 first). Runs on the
 * cusma3 scheduler node (data-sync profile) hourly 07–17 UTC and sends a
 * small batch per run — ~35 × 11 runs ≈ 385/day. Deliberately slow:
 * mailbox providers rate a sudden bulk blast from a transactional domain
 * as spam; a steady drip from the same server that already sends booking
 * mail keeps us in the inbox.
 *
 * Suppression is checked at claim time (service query), so an unsubscribe
 * mid-campaign stops all of that address's remaining sends. Sends run
 * SYNCHRONOUSLY (EmailService.syncSend) so SENT/FAILED reflects the real
 * SMTP outcome per recipient.
 */
@Component
@Profile("data-sync")
class CampaignMailJob(
    private val campaignService: CampaignService,
    private val emailService: EmailService,
    @Value("\${application.campaign.active:early-booking-2027}")
    private val activeCampaign: String,
    @Value("\${application.campaign.batch-size:35}")
    private val batchSize: Int,
    @Value("\${application.campaign.subject:Early Booking for Summer 2027 is open — save up to 20%}")
    private val subject: String,
    @Value("\${application.campaign.reply-to:info@boat4you.com}")
    private val replyTo: String,
    @Value("\${application.campaign.from-override:Boat4you - Europe Yachts Charter <info@boat4you.com>}")
    private val fromOverride: String,
    @Value("\${application.campaign.unsubscribe-base-url:https://api.boat4you.com}")
    private val unsubscribeBaseUrl: String,
    @Value("\${application.campaign.browse-url:https://www.boat4you.com/en/search?utm_source=email&utm_medium=email&utm_campaign=early_booking_2027}")
    private val browseUrl: String,
    /** Kill switch: nothing sends until this is flipped on (set in
     *  boat4youscheduler_vars.env as APPLICATION_CAMPAIGN_ENABLED=true
     *  once Mario approves the test email). */
    @Value("\${application.campaign.enabled:false}")
    private val enabled: Boolean,
    @Value("classpath:data/images/early-booking-hero.jpg")
    private val heroImage: Resource,
) {
    private val logger = LoggerFactory.getLogger(this::class.java.name)

    @Scheduled(cron = "0 12 7-17 * * *")
    fun sendPendingBatch() {
        if (!enabled) return
        val batch = campaignService.nextSendableBatch(activeCampaign, batchSize)
        if (batch.isEmpty()) return
        logger.info("Campaign '{}': sending batch of {}", activeCampaign, batch.size)

        var sent = 0
        var failed = 0
        batch.forEach { r ->
            val unsubscribeUrl = "$unsubscribeBaseUrl/public/newsletter/unsubscribe?token=${r.token}"
            try {
                emailService.sendEmail(
                    recipients = listOf(r.email),
                    subject = subject,
                    templateName = "email/earlyBooking2027",
                    variables = mapOf(
                        "name" to firstNameOf(r.recipientName),
                        "pastGuest" to (r.segment == "GUEST"),
                        "browseUrl" to browseUrl,
                        "unsubscribeUrl" to unsubscribeUrl,
                    ),
                    replyTo = replyTo,
                    fromOverride = fromOverride.takeIf { it.isNotBlank() },
                    extraInlineImages = mapOf("campaignHero" to heroImage),
                    extraHeaders = mapOf(
                        // RFC 8058 one-click unsubscribe — required by
                        // Gmail/Yahoo for bulk senders since 2024.
                        "List-Unsubscribe" to "<$unsubscribeUrl>",
                        "List-Unsubscribe-Post" to "List-Unsubscribe=One-Click",
                    ),
                    syncSend = true,
                )
                campaignService.markSent(r.id!!)
                sent++
            } catch (e: MailException) {
                campaignService.markFailed(r.id!!, e.message)
                failed++
            }
        }
        logger.info("Campaign '{}': batch done — {} sent, {} failed", activeCampaign, sent, failed)
    }

    /** "susanna zamparutti" → "Susanna"; null/garbage → "sailor" so the
     *  greeting still reads naturally ("Dear sailor,"). */
    private fun firstNameOf(name: String?): String {
        val first = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.length in 2..30 && it.any { c -> c.isLetter() } }
        return first?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "sailor"
    }
}
