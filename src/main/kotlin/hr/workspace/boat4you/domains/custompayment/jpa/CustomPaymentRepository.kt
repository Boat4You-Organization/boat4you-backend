package hr.workspace.boat4you.domains.custompayment.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface CustomPaymentRepository : JpaRepository<CustomPayment, Long> {
    fun findByPublicToken(publicToken: UUID): CustomPayment?

    fun findByProviderOrderId(providerOrderId: String): CustomPayment?

    fun existsByProviderOrderId(providerOrderId: String): Boolean

    /**
     * Row lock (`SELECT ... FOR UPDATE`) that serialises the webhook, the status endpoint, the reconcile job and admin
     * Refresh on one payment. Call it FIRST in the transaction (an entity already in the persistence context would not
     * be re-read).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CustomPayment p WHERE p.id = :id")
    fun lockById(
        @Param("id") id: Long,
    ): CustomPayment?

    /**
     * Serialises concurrent creates of the same (site, reference, amount, e-mail) — a double tap, a network retry — on
     * one advisory lock held until the transaction ends, so the dedupe check below cannot race (as InquiryRepository).
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) l", nativeQuery = true)
    fun lockDedupeKey(
        @Param("key") key: String,
    ): Int

    /** A still usable attempt with the same data: [status] (PENDING), created after [since], Mollie checkout attached. */
    @Query(
        """
        SELECT p FROM CustomPayment p
        WHERE p.status = :status
          AND p.site = :site
          AND p.reference = :reference
          AND p.amountMinor = :amountMinor
          AND LOWER(p.payerEmail) = LOWER(:email)
          AND p.createdAt > :since
          AND p.checkoutUrl IS NOT NULL
          AND COALESCE(p.method, 'CARD') = :method
        ORDER BY p.createdAt DESC
        """,
    )
    fun findDedupeCandidates(
        @Param("site") site: String,
        @Param("reference") reference: String,
        @Param("amountMinor") amountMinor: Long,
        @Param("email") email: String,
        @Param("since") since: Instant,
        @Param("status") status: CustomPaymentStatus,
        @Param("method") method: String = "CARD",
    ): List<CustomPayment>

    /** Attempts from this e-mail since [since] (any status) — the per-e-mail cap. */
    @Query("SELECT COUNT(p) FROM CustomPayment p WHERE LOWER(p.payerEmail) = LOWER(:email) AND p.createdAt > :since")
    fun countByEmailSince(
        @Param("email") email: String,
        @Param("since") since: Instant,
    ): Long

    /**
     * Claims one Mollie check for the status endpoint: stamps `last_checked_at` only when the row is PENDING and was
     * not checked in the last 10 s (database clock). 1 = go ask Mollie, 0 = someone just did / nothing to ask.
     */
    @Transactional
    @Modifying
    @Query(
        value = """
        UPDATE custom_payment SET last_checked_at = now()
        WHERE id = :id AND status = 'PENDING'
          AND (last_checked_at IS NULL OR last_checked_at < now() - interval '10 seconds')
        """,
        nativeQuery = true,
    )
    fun claimCheck(
        @Param("id") id: Long,
    ): Int

    /** Reconcile job: PENDING attempts with an order, younger than 48 h, not checked in the last 5 min (oldest first). */
    @Query(
        value = """
        SELECT id FROM custom_payment
        WHERE status = 'PENDING' AND provider_order_id IS NOT NULL
          AND created_at > now() - interval '48 hours'
          AND (last_checked_at IS NULL OR last_checked_at < now() - interval '5 minutes')
        ORDER BY created_at
        LIMIT 50
        """,
        nativeQuery = true,
    )
    fun findReconcileCandidateIds(): List<Long>

    /** Reconcile job: attempts still PENDING 48 h after they were created. */
    @Query(
        value = """
        SELECT id FROM custom_payment
        WHERE status = 'PENDING' AND created_at <= now() - interval '48 hours'
        ORDER BY created_at
        LIMIT 50
        """,
        nativeQuery = true,
    )
    fun findStalePendingIds(): List<Long>

    /** Reconcile job: rows whose Mollie payment was never attached (node died mid-create) -> FAILED after 15 min. */
    @Transactional
    @Modifying
    @Query(
        value = """
        UPDATE custom_payment SET status = 'FAILED'
        WHERE status = 'PENDING' AND provider_order_id IS NULL
          AND created_at < now() - interval '15 minutes'
        """,
        nativeQuery = true,
    )
    fun failUnattached(): Int

    // `CAST(:q AS string)` is required, see InquiryRepository: an untyped null would be inferred as bytea by PostgreSQL.
    @Query(
        """
        SELECT p FROM CustomPayment p
        WHERE (:status IS NULL OR p.status = :status)
          AND (:site IS NULL OR p.site = :site)
          AND (:q IS NULL
            OR LOWER(p.reference) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(p.payerEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(p.providerOrderId) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        ORDER BY p.createdAt DESC, p.id DESC
        """,
    )
    fun searchForAdmin(
        @Param("status") status: CustomPaymentStatus?,
        @Param("site") site: String?,
        @Param("q") q: String?,
        pageable: Pageable,
    ): Page<CustomPayment>

    /** Money received ([completed] minus refunded, cents) among the rows the same admin filters select. */
    @Query(
        """
        SELECT COALESCE(SUM(p.amountMinor - p.refundedAmountMinor), 0) FROM CustomPayment p
        WHERE p.status = :completed
          AND (:status IS NULL OR p.status = :status)
          AND (:site IS NULL OR p.site = :site)
          AND (:q IS NULL
            OR LOWER(p.reference) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(p.payerEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(p.providerOrderId) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        """,
    )
    fun sumCompletedNet(
        @Param("status") status: CustomPaymentStatus?,
        @Param("site") site: String?,
        @Param("q") q: String?,
        @Param("completed") completed: CustomPaymentStatus,
    ): Long

    /** Fee sync: `[providerOrderId, createdAt]` of the paid payments without a known Mollie fee, completed after [since]. */
    @Query(
        """
        SELECT p.providerOrderId, p.createdAt FROM CustomPayment p
        WHERE p.status = :completed AND p.providerFeeMinor IS NULL
          AND p.providerOrderId IS NOT NULL AND p.completedAt > :since
        """,
    )
    fun findFeeCandidates(
        @Param("completed") completed: CustomPaymentStatus,
        @Param("since") since: Instant,
    ): List<Array<Any?>>

    /** Fee sync: stores Mollie's fee once (never overwrites a stored one). */
    @Modifying
    @Transactional
    @Query("UPDATE CustomPayment p SET p.providerFeeMinor = :fee WHERE p.providerOrderId = :providerOrderId AND p.providerFeeMinor IS NULL")
    fun storeProviderFee(
        @Param("providerOrderId") providerOrderId: String,
        @Param("fee") fee: Long,
    ): Int

    /** GDPR Art. 15/20 export: the payments made with the user's e-mail (custom payments have no user FK). */
    fun findAllByPayerEmailIgnoreCase(payerEmail: String): List<CustomPayment>
}
