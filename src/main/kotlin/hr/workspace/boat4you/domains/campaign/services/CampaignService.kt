package hr.workspace.boat4you.domains.campaign.services

import hr.workspace.boat4you.domains.campaign.jpa.CampaignRecipient
import hr.workspace.boat4you.domains.campaign.jpa.CampaignRecipientRepository
import hr.workspace.boat4you.domains.campaign.jpa.CampaignRecipientStatus
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppression
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class RecipientImport(val email: String, val name: String? = null)

data class ImportResult(
    val imported: Int,
    val skippedInvalid: Int,
    val skippedExisting: Int,
    val markedSuppressed: Int,
)

@Service
class CampaignService(
    private val recipientRepository: CampaignRecipientRepository,
    private val suppressionRepository: EmailSuppressionRepository,
) {
    private val logger = LoggerFactory.getLogger(this::class.java.name)

    // Intentionally permissive — the import list is pre-cleaned; this only
    // keeps obvious garbage (no @, spaces, angle brackets) out of the queue.
    private val emailShape = Regex("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]{2,}$")

    /** Queue recipients for a campaign. Idempotent per (campaign, email):
     *  re-importing the same list only adds the new addresses. Addresses
     *  already on the do-not-mail list are queued as SUPPRESSED so the
     *  stats show them, but they can never be sent. */
    @Transactional
    fun bulkImport(
        campaign: String,
        recipients: List<RecipientImport>,
    ): ImportResult {
        var imported = 0
        var invalid = 0
        var existing = 0
        var suppressed = 0
        val seenInBatch = HashSet<String>()

        recipients.forEach { r ->
            val email = r.email.trim().lowercase()
            if (!emailShape.matches(email)) {
                invalid++
                return@forEach
            }
            if (!seenInBatch.add(email) || recipientRepository.existsByCampaignAndEmailIgnoreCase(campaign, email)) {
                existing++
                return@forEach
            }
            val isSuppressed = suppressionRepository.existsByEmailIgnoreCase(email)
            recipientRepository.save(
                CampaignRecipient().apply {
                    this.campaign = campaign
                    this.email = email
                    this.recipientName = r.name?.trim()?.takeIf { it.isNotEmpty() }?.take(255)
                    this.token = UUID.randomUUID().toString().replace("-", "")
                    this.status = if (isSuppressed) CampaignRecipientStatus.SUPPRESSED else CampaignRecipientStatus.PENDING
                },
            )
            if (isSuppressed) suppressed++ else imported++
        }

        logger.info(
            "Campaign '{}' import: {} queued, {} invalid, {} already queued, {} suppressed",
            campaign, imported, invalid, existing, suppressed,
        )
        return ImportResult(imported, invalid, existing, suppressed)
    }

    /** Unsubscribe by per-recipient token. Idempotent; unknown tokens are a
     *  no-op (the public endpoint must not leak which tokens exist). */
    @Transactional
    fun unsubscribeByToken(token: String): Boolean {
        val recipient = recipientRepository.findByToken(token.trim()) ?: return false
        suppress(recipient.email, "UNSUBSCRIBE_LINK")
        return true
    }

    /** Add to the permanent do-not-mail list and cancel every PENDING queue
     *  row for that address across all campaigns. */
    @Transactional
    fun suppress(
        email: String,
        reason: String,
    ) {
        val normalized = email.trim().lowercase()
        if (suppressionRepository.findByEmailIgnoreCase(normalized) == null) {
            suppressionRepository.save(
                EmailSuppression().apply {
                    this.email = normalized
                    this.reason = reason
                },
            )
        }
        recipientRepository.findByEmailIgnoreCase(normalized)
            .filter { it.status == CampaignRecipientStatus.PENDING }
            .forEach { it.status = CampaignRecipientStatus.SUPPRESSED }
    }

    @Transactional
    fun bulkSuppress(
        emails: List<String>,
        reason: String,
    ): Int {
        var added = 0
        emails.map { it.trim().lowercase() }.filter { emailShape.matches(it) }.distinct().forEach {
            if (suppressionRepository.findByEmailIgnoreCase(it) == null) {
                suppress(it, reason)
                added++
            }
        }
        return added
    }

    @Transactional(readOnly = true)
    fun nextSendableBatch(
        campaign: String,
        size: Int,
    ): List<CampaignRecipient> = recipientRepository.findNextSendableBatch(campaign, PageRequest.of(0, size))

    @Transactional
    fun markSent(id: Long) {
        recipientRepository.findById(id).ifPresent {
            it.status = CampaignRecipientStatus.SENT
            it.sentAt = Instant.now()
            it.error = null
        }
    }

    @Transactional
    fun markFailed(
        id: Long,
        message: String?,
    ) {
        recipientRepository.findById(id).ifPresent {
            it.status = CampaignRecipientStatus.FAILED
            it.error = message?.take(2000)
        }
    }

    @Transactional(readOnly = true)
    fun stats(campaign: String): Map<CampaignRecipientStatus, Long> =
        recipientRepository.countByStatusForCampaign(campaign).associate { it.status to it.cnt }
}
