package hr.workspace.boat4you.domains.social.web

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.social.dto.AutomationPostRequest
import hr.workspace.boat4you.domains.social.dto.MediaUrlRequest
import hr.workspace.boat4you.domains.social.dto.SocialMediaDto
import hr.workspace.boat4you.domains.social.dto.SocialPostDto
import hr.workspace.boat4you.domains.social.dto.SocialPostRequest
import hr.workspace.boat4you.domains.social.dto.SocialTargetRequest
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialApiKey
import hr.workspace.boat4you.domains.social.jpa.SocialMediaOrigin
import hr.workspace.boat4you.domains.social.jpa.SocialPostAction
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.service.SocialActor
import hr.workspace.boat4you.domains.social.service.SocialApiKeyService
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import hr.workspace.boat4you.domains.social.service.SocialPostFilter
import hr.workspace.boat4you.domains.social.service.SocialPostService
import hr.workspace.boat4you.domains.social.service.SocialValidationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.web.PagedModel
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Automation API for Objave (a scheduled Claude task, a script): header `X-Api-Key` (a key from Objave →
 * Automatizacija). permitAll in the security chain; every call authenticates the key itself (401 otherwise). A
 * drafts-only key always creates DRAFTs; a full key may schedule (never "publish now").
 */
@RestController
@RequestMapping("/api/social/v1")
class SocialApiController(
    private val keys: SocialApiKeyService,
    private val posts: SocialPostService,
    private val accounts: SocialAccountRepository,
    private val store: SocialMediaStore,
) {
    @GetMapping("/accounts")
    fun accounts(
        @RequestHeader(name = HEADER, required = false) key: String?,
    ): List<Map<String, Any?>> {
        auth(key)
        return accounts.findAllByDisconnectedAtIsNull().map {
            mapOf("id" to it.id, "platform" to it.platform, "site" to it.site, "name" to it.name, "handle" to it.handle, "status" to it.status)
        }
    }

    @PostMapping("/media", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mediaFromUrl(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestBody body: MediaUrlRequest,
    ): SocialMediaDto {
        auth(key)
        return posts.mediaDto(store.storeFromUrl(body.url, null))
    }

    @PostMapping("/media")
    fun upload(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestParam(required = false) fileName: String?,
        request: HttpServletRequest,
    ): SocialMediaDto {
        auth(key)
        return posts.mediaDto(store.store(request.inputStream, fileName, null, SocialMediaOrigin.UPLOAD))
    }

    @GetMapping("/posts")
    fun list(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) site: String?,
    ): PagedModel<SocialPostDto> {
        auth(key)
        return PagedModel(posts.list(SocialPostFilter(from = from, to = to, site = site, size = MAX_LIST)))
    }

    @PostMapping("/posts")
    fun create(
        @RequestHeader(name = HEADER, required = false) key: String?,
        @RequestBody body: AutomationPostRequest,
    ): SocialPostDto {
        val k = auth(key)
        if (body.site != null && !SiteIds.isKnown(body.site)) throw SocialValidationException("Unknown site ${body.site}")
        val media = body.mediaIds + body.mediaUrls.take(MAX_URLS).map { store.storeFromUrl(it, null).id!! }
        val chosen =
            if (body.accountIds.isNotEmpty()) {
                accounts.findAllById(body.accountIds)
            } else {
                accounts.findAllByDisconnectedAtIsNull().filter {
                    it.platform in body.platforms && it.status == SocialAccountStatus.CONNECTED && (body.site == null || it.site == body.site || it.site == null)
                }
            }
        val request =
            SocialPostRequest(
                text = body.text,
                link = body.link,
                mediaIds = media,
                targets = chosen.map { a -> body.perPlatform[a.platform].let { pp -> SocialTargetRequest(a.id!!, pp?.text, pp?.title) } },
                action = if (body.scheduledAt != null) SocialPostAction.SCHEDULE else SocialPostAction.DRAFT,
                scheduledAt = body.scheduledAt,
                note = body.note,
            )
        return posts.create(request, SocialActor(null, "API: ${k.name}", SocialPostSource.API, k.id, k.draftsOnly), body.site)
    }

    private fun auth(key: String?): SocialApiKey = keys.authenticate(key) ?: throw SocialUnauthorizedException()

    private companion object {
        const val HEADER = "X-Api-Key"
        const val MAX_LIST = 500
        const val MAX_URLS = 10
    }
}

class SocialUnauthorizedException : RuntimeException("Missing or unknown X-Api-Key")
