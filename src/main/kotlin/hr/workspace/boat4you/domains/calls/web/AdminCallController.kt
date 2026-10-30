package hr.workspace.boat4you.domains.calls.web

import hr.workspace.boat4you.domains.calls.dto.CallDetailDto
import hr.workspace.boat4you.domains.calls.dto.CallInquiryListItemDto
import hr.workspace.boat4you.domains.calls.dto.CallListItemDto
import hr.workspace.boat4you.domains.calls.dto.OfferSentRequest
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.service.AdminCallService
import hr.workspace.boat4you.domains.calls.service.CallRefusedException
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.data.web.PagedModel
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** The "Call centar" admin API (design §2.5), SYSTEM_ADMIN only: transcripts and recordings never leave this role. */
@RestController
@RequestMapping("/admin/calls")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCallController(
    private val service: AdminCallService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: CallStatus?,
        @RequestParam(required = false) outcome: CallOutcome?,
        @RequestParam(required = false) site: String?,
        @RequestParam(required = false) kind: CallKind?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PagedModel<CallListItemDto> = PagedModel(service.list(status, outcome, site, kind, from, to, q, page, size))

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: Long,
    ): ResponseEntity<CallDetailDto> = found { service.detail(id) }

    @GetMapping("/{id}/audio")
    fun audio(
        @PathVariable id: Long,
    ): ResponseEntity<Resource> {
        val file =
            try {
                service.audioFile(id)
            } catch (e: NoSuchElementException) {
                null
            } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(FileSystemResource(file))
    }

    @GetMapping("/inquiries")
    fun inquiries(
        @RequestParam(required = false) site: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PagedModel<CallInquiryListItemDto> = PagedModel(service.inquiryPage(site, q, page, size))

    @PostMapping("/inquiries/{inquiryId}/call-now")
    fun callNow(
        @PathVariable inquiryId: Long,
    ): ResponseEntity<Any> =
        try {
            ResponseEntity.ok(service.callNow(inquiryId))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        } catch (e: CallRefusedException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("skipReason" to e.reason.name))
        }

    @PostMapping("/inquiries/{inquiryId}/human-takeover")
    fun humanTakeover(
        @PathVariable inquiryId: Long,
    ): ResponseEntity<Void> = noContent { service.humanTakeover(inquiryId) }

    @PostMapping("/inquiries/{inquiryId}/offer-sent")
    fun offerSent(
        @PathVariable inquiryId: Long,
        @RequestBody(required = false) body: OfferSentRequest?,
    ): ResponseEntity<Void> = noContent { service.offerSent(inquiryId, body?.note) }

    @PostMapping("/inquiries/{inquiryId}/do-not-call")
    fun doNotCall(
        @PathVariable inquiryId: Long,
    ): ResponseEntity<Void> = noContent { service.doNotCall(inquiryId) }

    @PostMapping("/{id}/resolve")
    fun resolve(
        @PathVariable id: Long,
    ): ResponseEntity<Void> =
        try {
            noContent { service.resolve(id) }
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    @GetMapping("/settings")
    fun settings(): Map<String, String> = service.settingsView()

    @PutMapping("/settings")
    fun updateSettings(
        @RequestBody values: Map<String, String>,
    ): ResponseEntity<Void> = noContent { service.updateSettings(values) }

    /** 200 with the value, or 404 when the call or inquiry does not exist (no exception: the global handler would make it 500). */
    private fun <T : Any> found(block: () -> T): ResponseEntity<T> =
        try {
            ResponseEntity.ok(block())
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }

    private fun noContent(block: () -> Unit): ResponseEntity<Void> =
        try {
            block()
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
}
