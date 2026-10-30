package hr.workspace.boat4you.domains.invoice.jpa

import hr.workspace.boat4you.domains.reservation.jpa.ReservationView
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

@Repository
interface InvoiceRepository :
    JpaRepository<Invoice, Long>,
    JpaSpecificationExecutor<Invoice> {
    @Query(
        """
            SELECT i FROM Invoice i JOIN ReservationView rv ON i.reservationFlow.id = rv.reservationFlowId
            WHERE rv.reservationId = :reservationId AND rv.reservationUserId = :currentUserId
            ORDER BY i.created DESC
        """,
    )
    fun findByReservationIdAndUserId(
        reservationId: Long,
        currentUserId: Long,
    ): List<Invoice>

    @Query(
        """
        SELECT rv FROM ReservationView rv LEFT JOIN Invoice i ON rv.reservationFlowId = i.reservationFlow.id
        WHERE i IS NULL
        AND rv.reservationSysStatus = hr.workspace.boat4you.domains.reservation.enums.ReservationStatus.RESERVATION
        AND rv.reservationDateFrom >= :startOfDay
        AND rv.reservationDateFrom < :startOfNextDay
        ORDER BY rv.reservationId ASC
    """,
    )
    fun findReservationsWithoutInvoices(
        startOfDay: LocalDateTime,
        startOfNextDay: LocalDateTime,
    ): List<ReservationView>

    /**
     * Highest sequence already allocated in the yearly `NNNNNN/GGGG` numbering
     * scheme for the given year. Legacy plain-integer numbers (pre-2026 scheme)
     * don't match the pattern and are ignored on purpose.
     */
    @Query(
        value = """
            SELECT MAX(CAST(split_part(invoice_number, '/', 1) AS BIGINT))
            FROM invoice
            WHERE invoice_number ~ '^[0-9]+/[0-9]{4}${'$'}'
              AND split_part(invoice_number, '/', 2) = CAST(:year AS TEXT)
        """,
        nativeQuery = true,
    )
    fun findMaxSequenceForYear(year: Int): Long?

    /**
     * Transaction-scoped Postgres advisory lock serializing invoice-number
     * allocation across BOTH JVMs (cusma2 API + cusma3 scheduler job) — two
     * concurrent creators must not read the same MAX and mint duplicates.
     * Released automatically at transaction end.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(4272026) AS TEXT)", nativeQuery = true)
    fun lockInvoiceNumbering(): String?

    /**
     * Distinct invoice years, newest first — drives the year tabs in admin.
     * `invoice_date` is VARCHAR (V1_27) holding Hibernate's LocalDate text
     * (prod: `2026-04-04 +00`), so EXTRACT on it fails; the year is read from
     * the leading four digits and rows without one are skipped.
     */
    @Query(
        value = """
            SELECT DISTINCT CAST(left(invoice_date, 4) AS INTEGER)
            FROM invoice
            WHERE invoice_date ~ '^[0-9]{4}-'
            ORDER BY 1 DESC
        """,
        nativeQuery = true,
    )
    fun findDistinctYears(): List<Int>
}
