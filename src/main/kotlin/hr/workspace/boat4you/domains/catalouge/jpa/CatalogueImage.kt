package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A photo we hold ourselves (V9_89, 16.10.2026), outside the partner-owned [YachtImage]: the MMK / NauSys picture syncs
 * delete every yacht_image row the partner does not list, and nothing in the syncs, the image purge or ImageDownloadJob
 * reads this table. Today only [KIND_MARINA]: a photo of a departure marina ([locationId]), shown last in the boat
 * gallery with [credit] and [sourceUrl] (YachtGalleryService).
 *
 * Ids start at [FIRST_ID] (catalogue_image_id_seq; yacht_image_id_seq is capped one below), so `/public/image/{id}`
 * serves both tables by the number alone, through the same file store, resize gate and Cache-Control
 * (YachtImageService). [url] is the stored path under the upload directory, like [YachtImage.url]. A served id is
 * cached for 365 days as immutable (Bunny, browsers): a replacement photo gets a new row, never a new file under an old
 * id; an inactive row answers 404, but the CDN keeps a copy until it is purged.
 */
@Entity
@Table(name = "catalogue_image")
open class CatalogueImage {
    @Id
    @Column(name = "id", updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "kind", length = 16, nullable = false)
    open var kind: String = KIND_MARINA

    @Column(name = "location_id", nullable = false)
    open var locationId: Long? = null

    @Column(name = "url", length = 255, nullable = false)
    open var url: String? = null

    /** Public caption; when blank the boat page uses the marina's display name. */
    @Column(name = "caption", length = 255)
    open var caption: String? = null

    /** Public attribution ("Foto: Marina ..."), shown with the photo. */
    @Column(name = "credit", length = 255)
    open var credit: String? = null

    /** Where the photo was taken from; public (credit link) and the takedown trail. */
    @Column(name = "source_url", length = 1024)
    open var sourceUrl: String? = null

    /** Admin only: the terms the photo is used under (OWN, PERMISSION, CC-BY-SA-4.0, ...). */
    @Column(name = "licence", length = 64)
    open var licence: String? = null

    @Column(name = "\"position\"", nullable = false)
    open var position: Short = 0

    @Column(name = "active", nullable = false)
    open var active: Boolean = true

    @Column(name = "created", nullable = false, updatable = false)
    open var created: Instant = Instant.now()

    /** Admin only. */
    @Column(name = "created_by", length = 128)
    open var createdBy: String? = null

    companion object {
        /** First id of catalogue_image_id_seq; every yacht_image id is below it (V9_89). */
        const val FIRST_ID: Long = 2_000_000_000L

        const val KIND_MARINA = "MARINA"
    }
}
