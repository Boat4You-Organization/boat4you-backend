package hr.workspace.boat4you.domains.pr.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface PrOutletRepository : JpaRepository<PrOutlet, Long>

interface PrContactRepository : JpaRepository<PrContact, Long> {
    /** Every contact with this address, whatever the outlet (emails are stored normalised; the match ignores case too). */
    fun findAllByEmailIgnoreCase(email: String): List<PrContact>
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
