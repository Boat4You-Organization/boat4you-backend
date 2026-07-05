package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.TripChatMessageDto
import hr.workspace.boat4you.domains.reservation.dto.TripChatPostRequest
import hr.workspace.boat4you.domains.reservation.dto.TripDto
import hr.workspace.boat4you.domains.reservation.dto.TripEventRequest
import hr.workspace.boat4you.domains.reservation.dto.TripJoinRequest
import hr.workspace.boat4you.domains.reservation.dto.TripParticipantCredentialsDto
import hr.workspace.boat4you.domains.reservation.dto.TripParticipantDto
import hr.workspace.boat4you.domains.reservation.dto.TripPhotoDto
import hr.workspace.boat4you.domains.reservation.dto.TripPushSubscribeRequest
import hr.workspace.boat4you.domains.reservation.service.TripChatService
import hr.workspace.boat4you.domains.reservation.service.TripCrewService
import hr.workspace.boat4you.domains.reservation.service.TripJoinResult
import hr.workspace.boat4you.domains.reservation.service.TripPhotoService
import hr.workspace.boat4you.domains.reservation.service.TripPushService
import hr.workspace.boat4you.domains.reservation.service.TripQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Boat4You Trip — the /trip/{token} PWA hub backend. Unauthenticated by
 * design: the leader shares the URL with his crew, so the 32-hex unguessable
 * token IS the credential. Responses carry X-Robots-Tag noindex and expose
 * no prices, payments or PII beyond the leader's first name.
 */
@Tag(name = "Public Trip", description = "Trip-hub data for the Boat4You Trip PWA (token-keyed, crew-shareable)")
@RestController
@RequestMapping("/public/trip")
class PublicTripController(
    private val tripQueryService: TripQueryService,
    private val tripPushService: TripPushService,
    private val tripCrewService: TripCrewService,
    private val tripChatService: TripChatService,
    private val tripPhotoService: TripPhotoService,
) {
    @Operation(summary = "Trip hub payload by trip token (no prices / payments)")
    @GetMapping("/{token}")
    fun getTrip(@PathVariable token: String): ResponseEntity<TripDto> {
        val trip = tripQueryService.getTrip(token) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(trip)
    }

    @Operation(summary = "Download a travel document (crew list / boarding pass / preference list) scoped by trip token")
    @GetMapping("/{token}/documents/{documentId}")
    fun downloadTravelDocument(
        @PathVariable token: String,
        @PathVariable documentId: Long,
    ): ResponseEntity<ByteArray> {
        val doc = tripQueryService.getTravelDocument(token, documentId)
            ?: return ResponseEntity.notFound().build()
        val safeName = doc.filename.replace("\"", "")
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(doc.contentType)
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$safeName\"")
            set("X-Robots-Tag", "noindex, nofollow")
            contentLength = doc.data.size.toLong()
        }
        return ResponseEntity.ok().headers(headers).body(doc.data)
    }

    @Operation(summary = "Subscribe this device to trip push reminders (upsert by push endpoint)")
    @PostMapping("/{token}/push-subscriptions")
    fun subscribe(
        @PathVariable token: String,
        @RequestBody request: TripPushSubscribeRequest,
    ): ResponseEntity<Void> {
        if (!request.isValid()) return ResponseEntity.badRequest().build()
        val found = tripPushService.subscribe(
            token = token,
            endpoint = request.endpoint,
            p256dh = request.p256dh,
            auth = request.auth,
            ownerKey = request.ownerKey,
            userAgent = request.userAgent,
        )
        return if (found) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    @Operation(summary = "Record a trip analytics event (hub view, install, push open, site click)")
    @PostMapping("/{token}/events")
    fun recordEvent(
        @PathVariable token: String,
        @RequestBody request: TripEventRequest,
    ): ResponseEntity<Void> {
        val found = tripPushService.recordEvent(token, request.type, request.meta)
        return if (found) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    /* ------------------------- crew (closed group) ------------------------- */

    @Operation(summary = "Join the trip crew with just a name (unlocks after the first payment)")
    @PostMapping("/{token}/participants")
    fun join(
        @PathVariable token: String,
        @RequestBody request: TripJoinRequest,
    ): ResponseEntity<Any> = when (val result = tripCrewService.join(token, request.name)) {
        is TripJoinResult.Joined -> ResponseEntity.ok(result.credentials)
        is TripJoinResult.Refused -> when (result.reason.name) {
            "NOT_FOUND" -> ResponseEntity.notFound().build()
            else -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("reason" to result.reason.name))
        }
    }

    @Operation(summary = "Crew list — active participants only (requires a participant key)")
    @GetMapping("/{token}/participants")
    fun participants(
        @PathVariable token: String,
        @RequestParam key: String,
    ): ResponseEntity<List<TripParticipantDto>> {
        val list = tripCrewService.listActive(token, key) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(list)
    }

    @Operation(summary = "Leader removes a crew member")
    @DeleteMapping("/{token}/participants/{participantId}")
    fun removeParticipant(
        @PathVariable token: String,
        @PathVariable participantId: Long,
        @RequestParam key: String,
    ): ResponseEntity<Void> = if (tripCrewService.removeByOwner(token, key, participantId)) {
        ResponseEntity.noContent().build()
    } else {
        ResponseEntity.status(HttpStatus.FORBIDDEN).build()
    }

    /* ------------------------------- chat ---------------------------------- */

    @Operation(summary = "Chat history (last 200, optionally after sinceId)")
    @GetMapping("/{token}/chat")
    fun chatHistory(
        @PathVariable token: String,
        @RequestParam key: String,
        @RequestParam(defaultValue = "0") sinceId: Long,
    ): ResponseEntity<List<TripChatMessageDto>> {
        val messages = tripChatService.history(token, key, sinceId)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(messages)
    }

    @Operation(summary = "Post a chat message (writable until 14 days after the charter)")
    @PostMapping("/{token}/chat")
    fun postChat(
        @PathVariable token: String,
        @RequestBody request: TripChatPostRequest,
    ): ResponseEntity<TripChatMessageDto> {
        val message = tripChatService.post(token, request.key, request.body)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(message)
    }

    @Operation(summary = "Live chat stream (SSE)")
    @GetMapping("/{token}/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chatStream(
        @PathVariable token: String,
        @RequestParam key: String,
    ): ResponseEntity<SseEmitter> {
        val emitter = tripChatService.subscribe(token, key)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok()
            // Tells nginx not to buffer the stream — without it SSE arrives in bursts.
            .header("X-Accel-Buffering", "no")
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .body(emitter)
    }

    /* ------------------------------ album ---------------------------------- */

    @Operation(summary = "Crew photo album (metadata)")
    @GetMapping("/{token}/photos")
    fun photos(
        @PathVariable token: String,
        @RequestParam key: String,
    ): ResponseEntity<List<TripPhotoDto>> {
        val list = tripPhotoService.list(token, key) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(list)
    }

    @Operation(summary = "Upload a photo with the per-upload marketing-consent checkbox")
    @PostMapping("/{token}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPhoto(
        @PathVariable token: String,
        @RequestParam("file") file: MultipartFile,
        @RequestParam key: String,
        @RequestParam(defaultValue = "false") marketingConsent: Boolean,
    ): ResponseEntity<TripPhotoDto> {
        val photo = tripPhotoService.upload(token, key, file, marketingConsent)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(photo)
    }

    @Operation(summary = "Delete a photo — the uploader his own, the trip leader any (also the consent-withdrawal path)")
    @DeleteMapping("/{token}/photos/{photoId}")
    fun deletePhoto(
        @PathVariable token: String,
        @PathVariable photoId: Long,
        @RequestParam key: String,
    ): ResponseEntity<Void> = if (tripPhotoService.delete(token, key, photoId)) {
        ResponseEntity.noContent().build()
    } else {
        ResponseEntity.status(HttpStatus.FORBIDDEN).build()
    }

    @Operation(summary = "Photo bytes (webp), participant-key scoped")
    @GetMapping("/{token}/photos/{photoId}/raw")
    fun photoRaw(
        @PathVariable token: String,
        @PathVariable photoId: Long,
        @RequestParam key: String,
    ): ResponseEntity<Resource> {
        val (resource, contentType) = tripPhotoService.raw(token, key, photoId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(resource)
    }

    @Operation(summary = "Download the whole trip album as a ZIP (participant-key scoped)")
    @GetMapping("/{token}/album.zip")
    fun downloadAlbum(
        @PathVariable token: String,
        @RequestParam key: String,
    ): ResponseEntity<ByteArray> {
        val zip = tripPhotoService.zipForCrew(token, key) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trip-photos.zip\"")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(zip)
    }
}
