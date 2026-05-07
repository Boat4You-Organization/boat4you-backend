package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReservationDocumentRepository : JpaRepository<ReservationDocument, Long> {
    /** All documents attached to a reservation, newest first. Excludes the
     *  heavyweight `data` BYTEA via a projection so list views stay fast. */
    @Query(
        """
        SELECT new hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto(
            d.id, d.reservationId, d.filename, d.contentType, d.sizeBytes,
            d.uploadedBy, d.uploadedAt, d.isInternal
        )
        FROM ReservationDocument d
        WHERE d.reservationId = :reservationId
        ORDER BY d.uploadedAt DESC
        """,
    )
    fun findMetadataByReservationId(@Param("reservationId") reservationId: Long): List<ReservationDocumentDto>

    /** Only the customer-visible (non-internal) documents. Used by the customer
     *  reservation detail mapper so internal admin uploads stay hidden. */
    @Query(
        """
        SELECT new hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto(
            d.id, d.reservationId, d.filename, d.contentType, d.sizeBytes,
            d.uploadedBy, d.uploadedAt, d.isInternal
        )
        FROM ReservationDocument d
        WHERE d.reservationId = :reservationId AND d.isInternal = false
        ORDER BY d.uploadedAt DESC
        """,
    )
    fun findCustomerVisibleByReservationId(@Param("reservationId") reservationId: Long): List<ReservationDocumentDto>
}
