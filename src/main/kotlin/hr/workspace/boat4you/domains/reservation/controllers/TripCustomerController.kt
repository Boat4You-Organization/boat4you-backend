package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.TripParticipantCredentialsDto
import hr.workspace.boat4you.domains.reservation.service.TripCrewService
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Owner entry to the trip's closed group: the /trip SSR page calls this with
 * the booking owner's JWT and hands the returned participant key to the hub.
 * Guests never touch this — they join through the public endpoint.
 */
@Tag(name = "Trip (customer)")
@RestController
@RequestMapping("/secured/trip")
class TripCustomerController(
    private val tripCrewService: TripCrewService,
) {
    @Operation(summary = "Claim (or re-fetch) the OWNER participant for the authenticated booking owner")
    @PostMapping("/{token}/owner")
    fun claimOwner(@PathVariable token: String): ResponseEntity<TripParticipantCredentialsDto> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: return ResponseEntity.status(401).build()
        val credentials = tripCrewService.claimOwner(token, userId, fallbackName = null)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(credentials)
    }
}
