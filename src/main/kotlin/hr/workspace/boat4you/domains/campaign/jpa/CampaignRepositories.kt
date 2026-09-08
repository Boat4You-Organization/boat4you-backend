package hr.workspace.boat4you.domains.campaign.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface EmailSuppressionRepository : JpaRepository<EmailSuppression, Long> {
    fun existsByEmailIgnoreCase(email: String): Boolean

    fun findByEmailIgnoreCase(email: String): EmailSuppression?
}

@Repository
interface CampaignRecipientRepository : JpaRepository<CampaignRecipient, Long> {
    fun findByToken(token: String): CampaignRecipient?

    fun existsByCampaignAndEmailIgnoreCase(
        campaign: String,
        email: String,
    ): Boolean

    fun findByEmailIgnoreCase(email: String): List<CampaignRecipient>

    /** Next sendable rows: PENDING and not on the do-not-mail list. The
     *  suppression check happens HERE (send time), not at import time, so
     *  an unsubscribe that lands mid-campaign stops all later batches. */
    @Query(
        """
        SELECT r FROM CampaignRecipient r
        WHERE r.campaign = :campaign
          AND r.status = hr.workspace.boat4you.domains.campaign.jpa.CampaignRecipientStatus.PENDING
          AND lower(r.email) NOT IN (SELECT lower(s.email) FROM EmailSuppression s)
        ORDER BY r.id
        """,
    )
    fun findNextSendableBatch(
        campaign: String,
        pageable: Pageable,
    ): List<CampaignRecipient>

    @Query(
        """
        SELECT r.status AS status, count(r) AS cnt FROM CampaignRecipient r
        WHERE r.campaign = :campaign GROUP BY r.status
        """,
    )
    fun countByStatusForCampaign(campaign: String): List<StatusCount>

    interface StatusCount {
        val status: CampaignRecipientStatus
        val cnt: Long
    }
}
