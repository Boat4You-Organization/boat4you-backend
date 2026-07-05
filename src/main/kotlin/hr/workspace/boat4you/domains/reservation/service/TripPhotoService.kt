package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.reservation.dto.TripPhotoDto
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRole
import hr.workspace.boat4you.domains.reservation.jpa.TripPhoto
import hr.workspace.boat4you.domains.reservation.jpa.TripPhotoRepository
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime

/**
 * Crew photo album (phase 4). Uploads route through [FileSystemService] —
 * magic-byte validated, converted to webp, stored on the NFS upload dir under
 * trip-photos/{reservationId}. `marketingConsent` is the per-upload GDPR
 * checkbox (locked 4.7.2026); the admin sees the flag per photo and compiles
 * the post-charter album from it. Uploads close 30 days after the charter;
 * viewing stays open (memory mode is the point of the album).
 */
@Service
@Transactional(readOnly = true)
class TripPhotoService(
    private val photoRepository: TripPhotoRepository,
    private val crewService: TripCrewService,
    private val fileSystemService: FileSystemService,
) {
    @Transactional
    fun upload(
        token: String,
        participantKey: String,
        file: MultipartFile,
        marketingConsent: Boolean,
    ): TripPhotoDto? {
        val (reservation, participant) = crewService.findActiveParticipant(token, participantKey) ?: return null
        val uploadsCloseAt = reservation.dateTo?.plusDays(UPLOADS_CLOSE_AFTER_DAYS)
        if (uploadsCloseAt != null && LocalDateTime.now().isAfter(uploadsCloseAt)) return null
        if (photoRepository.countByReservationId(reservation.id!!) >= MAX_PHOTOS_PER_TRIP) return null

        val path = fileSystemService.saveImage(file, "trip-photos/${reservation.id}").getOrThrow()
        val saved = photoRepository.save(
            TripPhoto().apply {
                this.reservationId = reservation.id
                this.participantId = participant.id
                this.uploaderName = participant.name
                this.filePath = path
                this.contentType = "image/webp"
                this.sizeBytes = file.size
                this.marketingConsent = marketingConsent
            },
        )
        return saved.toDto()
    }

    fun list(token: String, participantKey: String): List<TripPhotoDto>? {
        val (reservation, _) = crewService.findActiveParticipant(token, participantKey) ?: return null
        return photoRepository.findAllByReservationIdOrderByIdDesc(reservation.id!!).map { it.toDto() }
    }

    fun raw(token: String, participantKey: String, photoId: Long): Pair<Resource, String>? {
        val (reservation, _) = crewService.findActiveParticipant(token, participantKey) ?: return null
        return rawInternal(reservation.id!!, photoId)
    }

    /** Uploader deletes his own photo; the trip leader may delete any. Also
     *  the consent-withdrawal path (GDPR): delete = consent gone with it. */
    @Transactional
    fun delete(token: String, participantKey: String, photoId: Long): Boolean {
        val (reservation, participant) = crewService.findActiveParticipant(token, participantKey) ?: return false
        val photo = photoRepository.findById(photoId).orElse(null) ?: return false
        if (photo.reservationId != reservation.id) return false
        val mayDelete = photo.participantId == participant.id || participant.role == TripParticipantRole.OWNER
        if (!mayDelete) return false
        deleteInternal(photo)
        return true
    }

    @Transactional
    fun adminDelete(reservationId: Long, photoId: Long): Boolean {
        val photo = photoRepository.findById(photoId).orElse(null) ?: return false
        if (photo.reservationId != reservationId) return false
        deleteInternal(photo)
        return true
    }

    private fun deleteInternal(photo: TripPhoto) {
        photo.filePath?.let { fileSystemService.deleteFile(it) }
        photoRepository.delete(photo)
    }

    fun adminList(reservationId: Long): List<TripPhotoDto> =
        photoRepository.findAllByReservationIdOrderByIdDesc(reservationId).map { it.toDto() }

    fun adminRaw(reservationId: Long, photoId: Long): Pair<Resource, String>? = rawInternal(reservationId, photoId)

    fun count(reservationId: Long): Long = photoRepository.countByReservationId(reservationId)

    /**
     * GDPR retention purge (Mario 5.7.2026): 10 days after the charter ends we
     * delete every trip photo — DB rows AND the NFS files. Run daily off the
     * scheduler node; [cutoff] = start-of-day 10 days ago. The crew is told the
     * exact deadline in the T+1 "album ready" post so nobody is surprised.
     */
    @Transactional
    fun purgeExpired(cutoff: java.time.LocalDateTime): Int {
        val expired = photoRepository.findExpired(cutoff)
        if (expired.isEmpty()) return 0
        expired.forEach { photo -> photo.filePath?.let { fileSystemService.deleteFile(it) } }
        photoRepository.deleteAll(expired)
        return expired.size
    }

    /**
     * Photo file descriptors for the crew "download all" (key-scoped). Only
     * the id + on-disk path are returned — the controller streams the ZIP
     * OUTSIDE any transaction (no DB connection pinned across NFS reads, no
     * whole-album-in-heap OOM on the single API node). Null = not a member;
     * empty = member but no photos.
     */
    fun crewAlbumFiles(token: String, participantKey: String): List<TripAlbumFile>? {
        val (reservation, _) = crewService.findActiveParticipant(token, participantKey) ?: return null
        return photoRepository.findAllByReservationIdOrderByIdDesc(reservation.id!!).toAlbumFiles()
    }

    /**
     * Admin download descriptors. [marketingOnly] keeps only the photos whose
     * uploader ticked the marketing-consent box — the ONLY ones we may reuse
     * for marketing (Mario 5.7.2026).
     */
    fun adminAlbumFiles(reservationId: Long, marketingOnly: Boolean): List<TripAlbumFile> =
        photoRepository.findAllByReservationIdOrderByIdDesc(reservationId)
            .filter { !marketingOnly || it.marketingConsent }
            .toAlbumFiles()

    private fun List<TripPhoto>.toAlbumFiles(): List<TripAlbumFile> =
        mapNotNull { p -> p.filePath?.let { TripAlbumFile(p.id!!, it) } }

    private fun rawInternal(reservationId: Long, photoId: Long): Pair<Resource, String>? {
        val photo = photoRepository.findById(photoId).orElse(null) ?: return null
        if (photo.reservationId != reservationId) return null
        val resource = runCatching {
            fileSystemService.getResourceFromPath(fileSystemService.getResourcePath(photo.filePath!!))
        }.getOrNull() ?: return null
        return resource to (photo.contentType ?: "image/webp")
    }

    private fun TripPhoto.toDto() = TripPhotoDto(
        id = id!!,
        participantId = participantId,
        uploaderName = uploaderName,
        marketingConsent = marketingConsent,
        createdAt = createdAt,
    )

    companion object {
        // Uploads close at the same 10-day mark the retention purge deletes at —
        // no point accepting a photo we'll remove the same day.
        private const val UPLOADS_CLOSE_AFTER_DAYS = 10L
        private const val MAX_PHOTOS_PER_TRIP = 300L
    }
}
