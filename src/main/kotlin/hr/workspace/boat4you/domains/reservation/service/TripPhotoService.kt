package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.reservation.dto.TripPhotoDto
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

    fun adminList(reservationId: Long): List<TripPhotoDto> =
        photoRepository.findAllByReservationIdOrderByIdDesc(reservationId).map { it.toDto() }

    fun adminRaw(reservationId: Long, photoId: Long): Pair<Resource, String>? = rawInternal(reservationId, photoId)

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
        uploaderName = uploaderName,
        marketingConsent = marketingConsent,
        createdAt = createdAt,
    )

    companion object {
        private const val UPLOADS_CLOSE_AFTER_DAYS = 30L
        private const val MAX_PHOTOS_PER_TRIP = 300L
    }
}
