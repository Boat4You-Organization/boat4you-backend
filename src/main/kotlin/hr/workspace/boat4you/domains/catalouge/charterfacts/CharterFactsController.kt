package hr.workspace.boat4you.domains.catalouge.charterfacts

import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import org.openapitools.model.ErrorSchema
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * Landing-page charter facts (`/search?destinations=…` pages): precomputed nightly on the scheduler node, served
 * from one row. every `/public` path is permitAll in SecurityConfiguration. An unknown vesselType is a 400 via
 * ApiErrorHandler's type-mismatch mapping; a malformed did is refused here before touching the database.
 */
@RestController
@RequestMapping("/public/charter-facts")
class CharterFactsController(
    private val readService: CharterFactsReadService,
) {
    @GetMapping
    fun getCharterFacts(
        @RequestParam did: String,
        @RequestParam(required = false) vesselType: VesselType?,
    ): ResponseEntity<Any> {
        if (!CharterFactsMath.isValidDid(did)) {
            return ResponseEntity.badRequest().body(
                ErrorSchema(
                    ApiErrorCodes.INVALID_REQUEST_PARAMETERS.code,
                    ApiErrorCodes.INVALID_REQUEST_PARAMETERS.message + ": {did=must be c-<id>, r-<id> or l-<id>}",
                ),
            )
        }
        val facts =
            readService.find(did, vesselType)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ErrorSchema(ApiErrorCodes.RESOURCE_NOT_FOUND.code, ApiErrorCodes.RESOURCE_NOT_FOUND.message),
                )
        // Facts change once a night; an hour at the CDN/browser keeps crawler bursts off the API node.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(facts)
    }
}
