package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Admin-uploaded document attached to a reservation — signed contract scans,
 * deposit receipts, customer correspondence PDFs, etc. Visible only to
 * admins, never exposed to customers.
 *
 * Files are persisted as PostgreSQL BYTEA. We use [JdbcTypeCode] +
 * [SqlTypes.VARBINARY] instead of `@Lob` because Hibernate's default LOB
 * mapping picks `oid` (Types#BLOB) for byte arrays, which fails
 * `ddl-auto=validate` against our `BYTEA` column. Volume per reservation
 * is small (a handful of files), so we trade a bit of DB size for transactional
 * safety + GDPR cascade-on-delete with the parent reservation.
 */
@Entity
@Table(name = "reservation_document")
open class ReservationDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @NotNull
    @Column(name = "filename", length = 255, nullable = false)
    open var filename: String? = null

    @NotNull
    @Column(name = "content_type", length = 100, nullable = false)
    open var contentType: String? = null

    @NotNull
    @Column(name = "size_bytes", nullable = false)
    open var sizeBytes: Long? = null

    @NotNull
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "data", nullable = false)
    open var data: ByteArray? = null

    /** Nullable: FK is `ON DELETE SET NULL` so deleting the uploader user
     *  doesn't cascade and wipe their historical uploads. */
    @Column(name = "uploaded_by")
    open var uploadedBy: Long? = null

    @NotNull
    @Column(name = "uploaded_at", nullable = false)
    open var uploadedAt: Instant? = null

    /** When true, the doc is admin-internal — never surfaced in the customer
     *  /my-bookings sidebar. Mario rule (3.5.2026): handover notes, agency
     *  back-office scans, accounting receipts stay private. Default false
     *  matches the pre-split behaviour where every upload reached the customer. */
    @NotNull
    @Column(name = "is_internal", nullable = false)
    open var isInternal: Boolean = false
}
