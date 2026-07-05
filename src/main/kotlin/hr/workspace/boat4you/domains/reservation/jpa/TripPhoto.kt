package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Crew-album photo metadata; the file itself lives on the NFS upload dir
 * (converted to webp on ingest, like yacht images). `marketingConsent` is
 * the per-upload GDPR checkbox — only consented photos may be reused.
 */
@Entity
@Table(name = "trip_photo")
open class TripPhoto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @Column(name = "participant_id")
    open var participantId: Long? = null

    @Column(name = "uploader_name", length = 120)
    open var uploaderName: String? = null

    @Column(name = "file_path", length = 511, nullable = false)
    open var filePath: String? = null

    @Column(name = "content_type", length = 100, nullable = false)
    open var contentType: String? = null

    @Column(name = "size_bytes", nullable = false)
    open var sizeBytes: Long? = null

    @Column(name = "marketing_consent", nullable = false)
    open var marketingConsent: Boolean = false

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
