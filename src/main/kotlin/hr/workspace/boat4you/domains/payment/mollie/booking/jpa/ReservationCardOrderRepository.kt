package hr.workspace.boat4you.domains.payment.mollie.booking.jpa

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

interface ReservationCardOrderRepository : JpaRepository<ReservationCardOrder, Long> {
    fun findByProviderOrderId(providerOrderId: String): ReservationCardOrder?

    fun existsByProviderOrderId(providerOrderId: String): Boolean

    fun findByCheckoutToken(checkoutToken: java.util.UUID): ReservationCardOrder?

    /** How many checkouts of [reservationFlowId] already used the web's [idempotencyKey] (the next one is a new attempt). */
    fun countByReservationFlowIdAndIdempotencyKey(
        reservationFlowId: Long,
        idempotencyKey: String,
    ): Long

    /**
     * Row lock (`SELECT ... FOR UPDATE`) that serialises the webhook, the status endpoint, the reconcile job and the
     * checkout on one order. Call it FIRST in the transaction (an entity already in the persistence context is not re-read).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM ReservationCardOrder o WHERE o.providerOrderId = :providerOrderId")
    fun lockByProviderOrderId(
        @Param("providerOrderId") providerOrderId: String,
    ): ReservationCardOrder?

    /** Same row lock as [lockByProviderOrderId], by ledger id. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM ReservationCardOrder o WHERE o.id = :id")
    fun lockById(
        @Param("id") id: Long,
    ): ReservationCardOrder?

    /**
     * `SET LOCAL lock_timeout = '5s'` for the rest of the current transaction (plan 2A.10 §3): a checkout waiting for a
     * locked phase / ledger row gives up after 5 s instead of holding a connection. Call it first in the transaction.
     */
    @Query(value = "SELECT set_config('lock_timeout', '5s', true)", nativeQuery = true)
    fun setLocalLockTimeout(): String

    /**
     * Transaction-scoped advisory lock on [key] (as CustomPaymentRepository.lockDedupeKey): serialises the checkouts of
     * one reservation flow, also when the charge has no phase rows to lock (legacy pay-full).
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) l", nativeQuery = true)
    fun lockKey(
        @Param("key") key: String,
    ): Int

    /**
     * Reuse before create: a PENDING order of [reservationFlowId] for exactly this charge — same phase set, same cents,
     * same `payment_phase_id` ([paymentPhaseKey] = the id, or -1 for NULL), same [method] group (a card checkout is
     * never handed out to a payer who picked Klarna, and vice versa) — that Mollie keeps open until after
     * [expiresAfter]. Newest first.
     */
    @Query(
        value = """
        SELECT * FROM reservation_card_order
        WHERE status = 'PENDING'
          AND reservation_flow_id = :reservationFlowId
          AND phase_ids = :phaseIds
          AND amount_minor = :amountMinor
          AND COALESCE(payment_phase_id, -1) = :paymentPhaseKey
          AND COALESCE(method, 'CARD') = :method
          AND expires_at > :expiresAfter
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findReusable(
        @Param("reservationFlowId") reservationFlowId: Long,
        @Param("phaseIds") phaseIds: String,
        @Param("amountMinor") amountMinor: Long,
        @Param("paymentPhaseKey") paymentPhaseKey: Long,
        @Param("expiresAfter") expiresAfter: Instant,
        @Param("method") method: String = BookingCheckoutMethod.CARD.name,
    ): ReservationCardOrder?

    /** Every PENDING order of [reservationFlowId], oldest first (the ones a new checkout must clean up first). */
    @Query(
        value = """
        SELECT * FROM reservation_card_order
        WHERE status = 'PENDING' AND reservation_flow_id = :reservationFlowId
        ORDER BY created_at, id
        """,
        nativeQuery = true,
    )
    fun findPendingByReservationFlow(
        @Param("reservationFlowId") reservationFlowId: Long,
    ): List<ReservationCardOrder>

    /**
     * Claims one Mollie check for the status endpoint: stamps `last_checked_at` only when the order is still open on our
     * side (PENDING or SUPERSEDED) and was not checked in the last 10 s (database clock). 1 = go ask Mollie, 0 = someone
     * just did / nothing to ask.
     */
    @Transactional
    @Modifying
    @Query(
        value = """
        UPDATE reservation_card_order SET last_checked_at = now()
        WHERE id = :id AND status IN ('PENDING', 'SUPERSEDED')
          AND (last_checked_at IS NULL OR last_checked_at < now() - interval '10 seconds')
        """,
        nativeQuery = true,
    )
    fun claimCheck(
        @Param("id") id: Long,
    ): Int

    /**
     * Reconcile job: PENDING / SUPERSEDED orders created 5 min - 48 h ago, not checked in the last 5 min, oldest first,
     * 50 per run (the backstop for missed webhooks and redirect-before-webhook).
     */
    @Query(
        value = """
        SELECT id FROM reservation_card_order
        WHERE status IN ('PENDING', 'SUPERSEDED')
          AND created_at < now() - interval '5 minutes'
          AND created_at > now() - interval '48 hours'
          AND (last_checked_at IS NULL OR last_checked_at < now() - interval '5 minutes')
        ORDER BY created_at
        LIMIT 50
        """,
        nativeQuery = true,
    )
    fun findReconcileCandidateIds(): List<Long>

    /** Still open on our side 48 h after creation (Mollie expires card checkouts long before): the office must look. */
    @Query(
        value = """
        SELECT id FROM reservation_card_order
        WHERE status IN ('PENDING', 'SUPERSEDED') AND created_at < now() - interval '48 hours'
        ORDER BY created_at
        LIMIT 50
        """,
        nativeQuery = true,
    )
    fun findStaleOpenIds(): List<Long>

    /** COMPLETED first payments whose booking confirmation never finished (node died after the commit) — alert only. */
    @Query(
        value = """
        SELECT id FROM reservation_card_order
        WHERE status = 'COMPLETED' AND promote_state = 'PENDING' AND completed_at < now() - interval '30 minutes'
        ORDER BY completed_at
        LIMIT 20
        """,
        nativeQuery = true,
    )
    fun findStuckPromoteIds(): List<Long>

    /** Another COMPLETED order of [reservationFlowId] than [exceptId] (double payment check for the legacy pay-full path). */
    @Query(
        value = """
        SELECT count(*) FROM reservation_card_order
        WHERE reservation_flow_id = :reservationFlowId AND status = 'COMPLETED' AND id <> :exceptId
        """,
        nativeQuery = true,
    )
    fun countOtherCompleted(
        @Param("reservationFlowId") reservationFlowId: Long,
        @Param("exceptId") exceptId: Long,
    ): Long

    /**
     * Admin "Payments" list: `[order, flow.name, flow.surname, flow.email, flow.phoneNumber, reservation.reservationNumber]`,
     * newest first. [statuses] is never empty (all statuses = no filter); [reservationId] -1 = any reservation.
     * `CAST(:q AS string)` as in CustomPaymentRepository (an untyped null would be inferred as bytea by PostgreSQL).
     */
    @Query(
        value = """
        SELECT o, f.name, f.surname, f.email, f.phoneNumber, r.reservationNumber
        FROM ReservationCardOrder o
        LEFT JOIN ReservationFlow f ON f.id = o.reservationFlowId
        LEFT JOIN Reservation r ON r.id = o.reservationId
        WHERE o.status IN :statuses
          AND (:reservationId = -1L OR o.reservationId = :reservationId)
          AND (:q IS NULL
            OR LOWER(o.reference) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(o.providerOrderId) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(r.reservationNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(f.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(CONCAT(COALESCE(f.name, ''), ' ', COALESCE(f.surname, ''))) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        ORDER BY o.createdAt DESC, o.id DESC
        """,
        countQuery = """
        SELECT COUNT(o)
        FROM ReservationCardOrder o
        LEFT JOIN ReservationFlow f ON f.id = o.reservationFlowId
        LEFT JOIN Reservation r ON r.id = o.reservationId
        WHERE o.status IN :statuses
          AND (:reservationId = -1L OR o.reservationId = :reservationId)
          AND (:q IS NULL
            OR LOWER(o.reference) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(o.providerOrderId) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(r.reservationNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(f.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(CONCAT(COALESCE(f.name, ''), ' ', COALESCE(f.surname, ''))) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        """,
    )
    fun searchForAdmin(
        @Param("statuses") statuses: Collection<ReservationCardOrderStatus>,
        @Param("reservationId") reservationId: Long,
        @Param("q") q: String?,
        pageable: Pageable,
    ): Page<Array<Any?>>

    /** One row of [searchForAdmin] (a list, because Spring Data unwraps a single `Object[]` result differently). */
    @Query(
        """
        SELECT o, f.name, f.surname, f.email, f.phoneNumber, r.reservationNumber
        FROM ReservationCardOrder o
        LEFT JOIN ReservationFlow f ON f.id = o.reservationFlowId
        LEFT JOIN Reservation r ON r.id = o.reservationId
        WHERE o.id = :id
        """,
    )
    fun findAdminRowById(
        @Param("id") id: Long,
    ): List<Array<Any?>>

    /** Money kept (COMPLETED: charged minus refunded minus charged back, cents) among the rows the same filters select. */
    @Query(
        """
        SELECT COALESCE(SUM(o.amountMinor - o.refundedAmountMinor - o.chargedBackAmountMinor), 0)
        FROM ReservationCardOrder o
        LEFT JOIN ReservationFlow f ON f.id = o.reservationFlowId
        LEFT JOIN Reservation r ON r.id = o.reservationId
        WHERE o.status = :completed AND o.status IN :statuses
          AND (:reservationId = -1L OR o.reservationId = :reservationId)
          AND (:q IS NULL
            OR LOWER(o.reference) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(o.providerOrderId) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(r.reservationNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(f.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            OR LOWER(CONCAT(COALESCE(f.name, ''), ' ', COALESCE(f.surname, ''))) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        """,
    )
    fun sumReceivedForAdmin(
        @Param("statuses") statuses: Collection<ReservationCardOrderStatus>,
        @Param("reservationId") reservationId: Long,
        @Param("q") q: String?,
        @Param("completed") completed: ReservationCardOrderStatus,
    ): Long
}
