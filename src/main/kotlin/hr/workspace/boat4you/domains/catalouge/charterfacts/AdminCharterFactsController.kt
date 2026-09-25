package hr.workspace.boat4you.domains.catalouge.charterfacts

import io.swagger.v3.oas.annotations.Operation
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Ops trigger for the nightly charter facts job — scheduler node only, like the other /admin job triggers. */
@RestController
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/charter-facts")
class AdminCharterFactsController(
    private val charterFactsJob: CharterFactsJob,
) {
    @Operation(
        summary = "Recompute the landing-page charter facts now",
        description = "Runs the nightly computation in the background under the same lock as the 04:20 UTC cron. " +
            "202 = started (result in the log, table replaced atomically when done); 409 = a run is already in progress.",
    )
    @PostMapping("/recompute")
    fun recompute(): ResponseEntity<Map<String, String>> =
        if (charterFactsJob.recomputeInBackground()) {
            ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("status" to "ALREADY_RUNNING"))
        }
}
