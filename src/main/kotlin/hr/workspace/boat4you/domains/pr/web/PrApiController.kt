package hr.workspace.boat4you.domains.pr.web

import hr.workspace.boat4you.domains.pr.dto.ImportResult
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchDto
import hr.workspace.boat4you.domains.pr.dto.PrStudyDto
import hr.workspace.boat4you.domains.pr.dto.PrStudyRequest
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrProperties
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * PR API for automated Claude sessions: header `X-Api-Key` must equal `pr.api-key` (env PR_API_KEY; blank = every call
 * 401). permitAll in the security chain; every call checks the key itself (401 `{code: UNAUTHORIZED}` otherwise). It
 * writes drafts, contacts and studies only: there is deliberately no send route, sending stays a click in the admin.
 */
@RestController
@RequestMapping("/api/pr/v1")
class PrApiController(
    private val props: PrProperties,
    private val pitches: PrPitchService,
    private val contacts: PrContactService,
    private val studies: PrStudyService,
) {
    @PostMapping("/pitches")
    fun createPitch(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestBody body: PrPitchDraftRequest,
    ): PrPitchDto {
        auth(key)
        return pitches.createDraft(body)
    }

    @GetMapping("/pitches")
    fun pitches(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestParam(required = false) studyId: Long?,
    ): List<PrPitchDto> {
        auth(key)
        return pitches.list(studyId, null)
    }

    /** The CSV file as the body (`text/csv`, UTF-8), header row first; see PrContactService.importCsv. */
    @PostMapping("/contacts/import", consumes = ["text/csv", "text/plain"])
    fun import(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestBody body: ByteArray,
    ): ImportResult {
        auth(key)
        return contacts.importCsv(body.toString(Charsets.UTF_8))
    }

    @PostMapping("/studies")
    fun createStudy(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestBody body: PrStudyRequest,
    ): PrStudyDto {
        auth(key)
        return studies.create(body)
    }

    /**
     * Any other path or method under /api/pr/v1 (a send route, say: there is none) is a plain 404. Without this the
     * global ApiErrorHandler would answer the NoHandlerFoundException with a 500.
     */
    @RequestMapping("/**")
    fun unknownRoute(): Nothing = throw PrException.notFound("NOT_FOUND", "No such PR API route (the PR API never sends)")

    /** Constant-time compare, so the key cannot be guessed byte by byte from response times. */
    private fun auth(key: String?) {
        val expected = props.apiKey.trim()
        val ok = expected.isNotEmpty() && key != null && MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        if (!ok) throw PrException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or wrong X-Api-Key")
    }

    private companion object {
        const val HEADER = "X-Api-Key"
    }
}
