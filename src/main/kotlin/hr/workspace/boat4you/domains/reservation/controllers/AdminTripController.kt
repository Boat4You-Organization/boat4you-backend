package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.TripChatMessageDto
import hr.workspace.boat4you.domains.reservation.dto.TripParticipantDto
import hr.workspace.boat4you.domains.reservation.dto.TripPhotoDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.TripChatService
import hr.workspace.boat4you.domains.reservation.service.TripCrewService
import hr.workspace.boat4you.domains.reservation.service.TripPhotoService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AdminTripChatPostRequest(val body: String)

/**
 * Admin side of Boat4You Trip: chat as the Concierge (with crew push),
 * participant management and the photo album with per-photo marketing
 * consent. Opening the chat stamps the unread-badge marker.
 */
@Tag(name = "Trip (admin)")
@RestController
@RequestMapping("/admin/trip")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminTripController(
    private val tripChatService: TripChatService,
    private val tripCrewService: TripCrewService,
    private val tripPhotoService: TripPhotoService,
    private val reservationRepository: ReservationRepository,
) {
    @Operation(summary = "Chat history (marks the booking's chat as seen)")
    @GetMapping("/{reservationId}/chat")
    fun chat(@PathVariable reservationId: Long): ResponseEntity<List<TripChatMessageDto>> =
        ResponseEntity.ok(tripChatService.adminHistory(reservationId))

    @Operation(summary = "Post as Boat4You Concierge (also pushes to the crew's devices)")
    @PostMapping("/{reservationId}/chat")
    fun postChat(
        @PathVariable reservationId: Long,
        @RequestBody request: AdminTripChatPostRequest,
    ): ResponseEntity<TripChatMessageDto> {
        val message = tripChatService.postAsConcierge(reservationId, request.body)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(message)
    }

    @Operation(summary = "All participants incl. removed")
    @GetMapping("/{reservationId}/participants")
    fun participants(@PathVariable reservationId: Long): ResponseEntity<List<TripParticipantDto>> =
        ResponseEntity.ok(tripCrewService.listForAdmin(reservationId))

    @Operation(summary = "Remove a participant (admin)")
    @DeleteMapping("/{reservationId}/participants/{participantId}")
    fun removeParticipant(
        @PathVariable reservationId: Long,
        @PathVariable participantId: Long,
    ): ResponseEntity<Void> = if (tripCrewService.removeByAdmin(reservationId, participantId)) {
        ResponseEntity.noContent().build()
    } else {
        ResponseEntity.notFound().build()
    }

    @Operation(summary = "Album metadata incl. marketing consent flags")
    @GetMapping("/{reservationId}/photos")
    fun photos(@PathVariable reservationId: Long): ResponseEntity<List<TripPhotoDto>> =
        ResponseEntity.ok(tripPhotoService.adminList(reservationId))

    @Operation(summary = "Photo bytes")
    @GetMapping("/{reservationId}/photos/{photoId}/raw")
    fun photoRaw(
        @PathVariable reservationId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Resource> {
        val (resource, contentType) = tripPhotoService.adminRaw(reservationId, photoId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .body(resource)
    }

    @Operation(summary = "Regenerate the trip link — the old token (and every shared link) dies instantly")
    @PostMapping("/{reservationId}/regenerate-token")
    @Transactional
    fun regenerateToken(@PathVariable reservationId: Long): ResponseEntity<Map<String, String>> {
        val reservation = reservationRepository.findById(reservationId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        reservation.tripToken = UUID.randomUUID().toString().replace("-", "")
        reservationRepository.save(reservation)
        return ResponseEntity.ok(mapOf("tripToken" to reservation.tripToken!!))
    }
}
