package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationDocument
import hr.workspace.boat4you.domains.reservation.jpa.ReservationDocumentRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

/**
 * Admin-only document attachments per reservation (signed contracts, deposit
 * receipts, correspondence). PDF/DOC/DOCX, &le;20MB, stored as DB BYTEA so
 * deletes cascade with the reservation.
 */
@Service
@Transactional(readOnly = true)
class ReservationDocumentService(
    private val reservationDocumentRepository: ReservationDocumentRepository,
    private val reservationRepository: ReservationRepository,
) {
    fun list(reservationId: Long): List<ReservationDocumentDto> {
        ensureReservationExists(reservationId)
        return reservationDocumentRepository.findMetadataByReservationId(reservationId)
    }

    /** Customer-visible only — strips internal admin uploads. Used by the
     *  reservation mapper for /my-bookings/{id} response. */
    fun listCustomerVisible(reservationId: Long): List<ReservationDocumentDto> {
        ensureReservationExists(reservationId)
        return reservationDocumentRepository.findCustomerVisibleByReservationId(reservationId)
    }

    @Transactional
    fun upload(
        reservationId: Long,
        file: MultipartFile,
        uploadedBy: Long?,
        isInternal: Boolean = false,
    ): ReservationDocumentDto {
        ensureReservationExists(reservationId)

        if (file.isEmpty) {
            throw IllegalArgumentException("Uploaded file is empty")
        }
        if (file.size > MAX_SIZE_BYTES) {
            throw IllegalArgumentException("File exceeds the ${MAX_SIZE_BYTES / 1024 / 1024} MB limit")
        }

        // Sanitize filename: strip any path components a (mis)configured
        // browser might include and clamp to the column width. We do this
        // BEFORE the MIME check because the extension fallback below
        // depends on the cleaned name.
        val safeFilename = sanitizeFilename(file.originalFilename ?: "document")

        val rawContentType = file.contentType?.lowercase()?.trim()
        val resolvedContentType = resolveContentType(rawContentType, safeFilename)
            ?: throw IllegalArgumentException("Only PDF, DOC, or DOCX files are allowed")

        val entity = ReservationDocument().apply {
            this.reservationId = reservationId
            this.filename = safeFilename
            this.contentType = resolvedContentType
            this.sizeBytes = file.size
            this.data = file.bytes
            this.uploadedBy = uploadedBy
            this.uploadedAt = Instant.now()
            this.isInternal = isInternal
        }
        val saved = reservationDocumentRepository.save(entity)
        return ReservationDocumentDto(
            id = saved.id!!,
            reservationId = saved.reservationId!!,
            filename = saved.filename!!,
            contentType = saved.contentType!!,
            sizeBytes = saved.sizeBytes!!,
            uploadedBy = saved.uploadedBy,
            uploadedAt = saved.uploadedAt!!,
            isInternal = saved.isInternal,
        )
    }

    fun download(documentId: Long): ReservationDocumentDownload {
        val doc = reservationDocumentRepository.findById(documentId)
            .orElseThrow { NoSuchElementException("Document $documentId not found") }
        return ReservationDocumentDownload(
            reservationId = doc.reservationId!!,
            filename = doc.filename!!,
            contentType = doc.contentType!!,
            data = doc.data!!,
            isInternal = doc.isInternal,
        )
    }

    @Transactional
    fun delete(documentId: Long) {
        // Idempotent — repeated DELETE on a missing document is a no-op so
        // the FE doesn't have to coordinate retries with concurrent admins.
        if (reservationDocumentRepository.existsById(documentId)) {
            reservationDocumentRepository.deleteById(documentId)
        }
    }

    private fun ensureReservationExists(reservationId: Long) {
        if (!reservationRepository.existsById(reservationId)) {
            throw IllegalArgumentException("Reservation $reservationId does not exist")
        }
    }

    private fun sanitizeFilename(raw: String): String {
        // Drop any directory prefix the browser may have leaked (Windows or
        // POSIX) and trim before clamping to the DB column width.
        val basename = raw.replace('\\', '/').substringAfterLast('/').trim()
        val clean = if (basename.isBlank()) "document" else basename
        return if (clean.length > FILENAME_MAX_LEN) clean.take(FILENAME_MAX_LEN) else clean
    }

    /** Returns a canonical MIME type when the upload looks like PDF/DOC/DOCX,
     *  or null when neither the MIME header nor the file extension match.
     *  Browsers occasionally send `application/octet-stream` for Office files,
     *  which is why the extension fallback exists. */
    private fun resolveContentType(rawContentType: String?, filename: String): String? {
        if (rawContentType != null && rawContentType in ALLOWED_CONTENT_TYPES) {
            return rawContentType
        }
        val lowerName = filename.lowercase()
        return when {
            lowerName.endsWith(".pdf") -> "application/pdf"
            lowerName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lowerName.endsWith(".doc") -> "application/msword"
            else -> null
        }
    }

    companion object {
        private const val MAX_SIZE_BYTES = 20L * 1024 * 1024
        private const val FILENAME_MAX_LEN = 255
        private val ALLOWED_CONTENT_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }
}

/** In-memory view of a stored document, used only for download responses. */
data class ReservationDocumentDownload(
    val reservationId: Long,
    val filename: String,
    val contentType: String,
    val data: ByteArray,
    val isInternal: Boolean,
)
