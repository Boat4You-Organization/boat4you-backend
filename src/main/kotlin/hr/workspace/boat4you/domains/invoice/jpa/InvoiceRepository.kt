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

    @Query(
        """
        SELECT i.invoiceNumber FROM Invoice i
        WHERE i.invoiceDate <= :onDate
        ORDER BY i.id DESC
        LIMIT 1
    """,
    )
    fun findLastInvoiceNumber(onDate: LocalDate): String?
}
