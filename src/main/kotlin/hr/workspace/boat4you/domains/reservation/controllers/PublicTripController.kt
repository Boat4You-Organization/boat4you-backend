package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.TripDto
import hr.workspace.boat4you.domains.reservation.service.TripQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
}
