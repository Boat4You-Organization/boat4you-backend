package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripPushSubscriptionRepository
import hr.workspace.boat4you.domains.reservation.service.TripPhotoService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class TripAlbumSummaryDto(val total: Long, val marketingConsented: Int)

/**
 * Admin side of Boat4You Trip — GDPR-minimal (Mario 5.7.2026): the broker has
 * NO access to the crew chat, the participant list or individual photos. What
 * remains is the trip-link kill-switch and an on-demand album DOWNLOAD (all
 * photos, or only the marketing-consented ones we're allowed to reuse) — a
 * compiled artifact fetched only when needed, not a browsable feed of the
 * guests' private trip content.
 */
@Tag(name = "Trip (admin)")
@RestController
@RequestMapping("/admin/trip")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminTripController(
    private val tripPhotoService: TripPhotoService,
    private val reservationRepository: ReservationRepository,
    private val pushSubscriptionRepository: TripPushSubscriptionRepository,
) {
    @Operation(summary = "Album counts (total + how many carry marketing consent) — no image data")
    @GetMapping("/{reservationId}/album-summary")
    fun albumSummary(@PathVariable reservationId: Long): ResponseEntity<TripAlbumSummaryDto> {
        val photos = tripPhotoService.adminList(reservationId)
        return ResponseEntity.ok(
            TripAlbumSummaryDto(total = photos.size.toLong(), marketingConsented = photos.count { it.marketingConsent }),
        )
    }

    @Operation(summary = "Download the album as a ZIP — all photos, or with ?marketingOnly=true only the consented ones")
    @GetMapping("/{reservationId}/album.zip")
    fun downloadAlbum(
        @PathVariable reservationId: Long,
        @RequestParam(defaultValue = "false") marketingOnly: Boolean,
    ): ResponseEntity<ByteArray> {
        val zip = tripPhotoService.adminZip(reservationId, marketingOnly) ?: return ResponseEntity.notFound().build()
        val name = if (marketingOnly) "trip-$reservationId-marketing.zip" else "trip-$reservationId-photos.zip"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
            .body(zip)
    }

    @Operation(summary = "Regenerate the trip link — the old token (and every shared link) dies instantly")
    @PostMapping("/{reservationId}/regenerate-token")
    @Transactional
    fun regenerateToken(@PathVariable reservationId: Long): ResponseEntity<Map<String, String>> {
        val reservation = reservationRepository.findById(reservationId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        reservation.tripToken = UUID.randomUUID().toString().replace("-", "")
        reservationRepository.save(reservation)
        // Devices subscribed under the OLD link must not keep receiving pushes
        // (each push carries the NEW secret URL — that would defeat the rotation).
        pushSubscriptionRepository.deleteAll(pushSubscriptionRepository.findAllByReservationId(reservationId))
        return ResponseEntity.ok(mapOf("tripToken" to reservation.tripToken!!))
    }
}
