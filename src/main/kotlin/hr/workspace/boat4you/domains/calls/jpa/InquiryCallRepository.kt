package hr.workspace.boat4you.domains.calls.jpa

import hr.workspace.boat4you.domains.calls.enums.CallStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface InquiryCallRepository : JpaRepository<InquiryCall, Long> {
    /** Due dialable calls, oldest first, locking them; rows another transaction holds are skipped. Call inside a transaction. */
    @Query(
        value =
            "SELECT id FROM inquiry_call WHERE status = 'SCHEDULED' AND kind IN ('CONFIRMATION', 'FOLLOW_UP', 'MANUAL') " +
                "AND scheduled_at <= :now ORDER BY scheduled_at, id FOR UPDATE SKIP LOCKED LIMIT :limit",
        nativeQuery = true,
    )
    fun claimDue(
        @Param("now") now: Instant,
        @Param("limit") limit: Int,
    ): List<Long>

    fun findByElevenlabsConversationId(conversationId: String): InquiryCall?

    fun findByInquiryIdOrderByCreatedAtDesc(inquiryId: Long): List<InquiryCall>

    /** Calls actually started in [from, to): everything except SCHEDULED, CANCELLED and SKIPPED. */
    @Query(
        "SELECT COUNT(c) FROM InquiryCall c WHERE c.startedAt >= :from AND c.startedAt < :to " +
            "AND c.status NOT IN (hr.workspace.boat4you.domains.calls.enums.CallStatus.SCHEDULED, " +
            "hr.workspace.boat4you.domains.calls.enums.CallStatus.CANCELLED, hr.workspace.boat4you.domains.calls.enums.CallStatus.SKIPPED)",
    )
    fun countStartedBetween(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /** A finished confirmation call to this number since [since] on another inquiry (same person, design §2.2). */
    @Query(
        "SELECT COUNT(c) > 0 FROM InquiryCall c WHERE c.toNumber = :phone " +
            "AND c.kind = hr.workspace.boat4you.domains.calls.enums.CallKind.CONFIRMATION " +
            "AND c.status = hr.workspace.boat4you.domains.calls.enums.CallStatus.DONE " +
            "AND c.endedAt >= :since AND c.inquiryId <> :excludeInquiryId",
    )
    fun existsDoneConfirmationForPhoneSince(
        @Param("phone") phoneE164: String,
        @Param("since") since: Instant,
        @Param("excludeInquiryId") excludeInquiryId: Long,
    ): Boolean

    /** Calls still dialling or in progress that started before [before] (lost webhook, deploy gap). */
    @Query(
        "SELECT c FROM InquiryCall c WHERE c.status IN :statuses AND c.startedAt < :before ORDER BY c.startedAt",
    )
    fun findStuck(
        @Param("before") before: Instant,
        @Param("statuses") statuses: Collection<CallStatus> = listOf(CallStatus.DIALING, CallStatus.IN_PROGRESS),
    ): List<InquiryCall>

    /** Calls whose recording is older than the retention (by end time). */
    @Query("SELECT c FROM InquiryCall c WHERE c.audioPath IS NOT NULL AND c.endedAt < :before")
    fun findWithAudioEndedBefore(
        @Param("before") before: Instant,
    ): List<InquiryCall>
}
