package hr.workspace.boat4you.domains.pr.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface PrOutletRepository : JpaRepository<PrOutlet, Long> {
    /** The unique key of pr_outlet: (lower(name), market). */
    fun findFirstByNameIgnoreCaseAndMarket(
        name: String,
        market: String,
    ): PrOutlet?
}

interface PrContactRepository :
    JpaRepository<PrContact, Long>,
    JpaSpecificationExecutor<PrContact> {
    /** Every contact with this address, whatever the outlet (emails are stored normalised; the match ignores case too). */
    fun findAllByEmailIgnoreCase(email: String): List<PrContact>

    /** The unique key of pr_contact: (lower(email), outlet); a null outletId matches a contact without an outlet. */
    fun findFirstByEmailIgnoreCaseAndOutletId(
        email: String,
        outletId: Long?,
    ): PrContact?

    /** 24-month retention: one DELETE, the contacts' pitches go with them (ON DELETE CASCADE); email_suppression is untouched. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pr_contact WHERE coalesce(last_contacted_at, created_at) < :cutoff", nativeQuery = true)
    fun deleteInactiveSince(cutoff: Instant): Int
}

interface PrStudyRepository : JpaRepository<PrStudy, Long>

interface PrPitchRepository : JpaRepository<PrPitch, Long> {
    /** Atomic DRAFT -> SENDING: 1 = this caller may send, 0 = someone else already took it (or it is not a draft). */
    @Modifying
    @Transactional
    @Query(value = "UPDATE pr_pitch SET status = 'SENDING', updated_at = now() WHERE id = :id AND status = 'DRAFT'", nativeQuery = true)
    fun markSending(id: Long): Int
}

interface PrPlacementRepository : JpaRepository<PrPlacement, Long>

interface PrMentionRepository : JpaRepository<PrMention, Long> {
    fun existsByUrl(url: String): Boolean
}

interface PrAlertFeedRepository : JpaRepository<PrAlertFeed, Long>
