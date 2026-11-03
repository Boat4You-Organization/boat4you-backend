package hr.workspace.boat4you.domains.social.web

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.domains.social.ai.SocialAiService
import hr.workspace.boat4you.domains.social.dto.AccountPatch
import hr.workspace.boat4you.domains.social.dto.ApiKeyRequest
import hr.workspace.boat4you.domains.social.dto.ConnectRequest
import hr.workspace.boat4you.domains.social.dto.CredentialsRequest
import hr.workspace.boat4you.domains.social.dto.SocialAccountDto
import hr.workspace.boat4you.domains.social.dto.SocialAiOverviewDto
import hr.workspace.boat4you.domains.social.dto.SocialAiRunDto
import hr.workspace.boat4you.domains.social.dto.SocialAiSettingDto
import hr.workspace.boat4you.domains.social.dto.SocialApiKeyDto
import hr.workspace.boat4you.domains.social.dto.SocialMediaDto
import hr.workspace.boat4you.domains.social.dto.SocialPostDto
import hr.workspace.boat4you.domains.social.dto.SocialPostRequest
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.jpa.SocialPostStatus
import hr.workspace.boat4you.domains.social.service.SocialAccountService
import hr.workspace.boat4you.domains.social.service.SocialActor
import hr.workspace.boat4you.domains.social.service.SocialApiKeyService
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import hr.workspace.boat4you.domains.social.service.SocialPostFilter
import hr.workspace.boat4you.domains.social.service.SocialPostService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.web.PagedModel
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Objave admin API (design: boat4you-admin docs/superpowers/specs/2026-11-03-social-publisher-design.md), SYSTEM_ADMIN
 * only. Errors: 400 / 404 / 409 with `{code, message}` (SocialErrorAdvice). No network token is ever in an answer.
 */
@RestController
@RequestMapping("/admin/social")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Suppress("TooManyFunctions") // one function per endpoint of the Objave page
class AdminSocialController(
    private val posts: SocialPostService,
    private val accounts: SocialAccountService,
    private val keys: SocialApiKeyService,
    private val ai: SocialAiService,
    private val store: SocialMediaStore,
) {
    // ---- Channels ----

    @GetMapping("/accounts")
    fun accounts(): List<SocialAccountDto> = accounts.list()

    /** Which networks this server can connect, and why not (missing app keys). */
    @GetMapping("/platforms")
    fun platforms(): Map<SocialPlatform, String?> = accounts.availability()

    @PostMapping("/accounts/connect/{platform}")
    fun connect(
        @PathVariable platform: SocialPlatform,
        @RequestBody body: ConnectRequest,
    ): Map<String, String> = mapOf("authorizeUrl" to accounts.startConnect(platform, body.site, body.returnUrl, actor().userId!!))

    @PostMapping("/accounts/credentials")
    fun credentials(
        @RequestBody body: CredentialsRequest,
    ): List<SocialAccountDto> = accounts.connectWithCredentials(body.platform, body.identifier, body.secret, body.site, actor().userId!!)

    @PatchMapping("/accounts/{id}")
    fun patchAccount(
        @PathVariable id: Long,
        @RequestBody body: AccountPatch,
    ): SocialAccountDto = accounts.setSite(id, body.site)

    @DeleteMapping("/accounts/{id}")
    fun disconnect(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        accounts.disconnect(id)
        return ResponseEntity.noContent().build()
    }

    // ---- Media: the raw file as the body (Content-Type = the file's type, ?fileName=), not multipart ----

    @PostMapping("/media")
    fun upload(
        request: HttpServletRequest,
        @RequestParam(required = false) fileName: String?,
    ): SocialMediaDto = posts.mediaDto(store.store(request.inputStream, fileName, actor().userId))

    // ---- Posts ----

    @GetMapping("/posts")
    @Suppress("LongParameterList") // the list filters
    fun list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) status: SocialPostStatus?,
        @RequestParam(required = false) platform: SocialPlatform?,
        @RequestParam(required = false) site: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): PagedModel<SocialPostDto> = PagedModel(posts.list(SocialPostFilter(from, to, status, platform, site, page, size)))

    @GetMapping("/posts/{id}")
    fun get(
        @PathVariable id: Long,
    ): SocialPostDto = posts.get(id)

    @PostMapping("/posts")
    fun create(
        @RequestBody body: SocialPostRequest,
    ): SocialPostDto = posts.create(body, actor())

    @PutMapping("/posts/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: SocialPostRequest,
    ): SocialPostDto = posts.update(id, body, actor())

    @PostMapping("/posts/{id}/cancel")
    fun cancel(
        @PathVariable id: Long,
    ): SocialPostDto = posts.cancel(id)

    @PostMapping("/posts/{id}/retry")
    fun retry(
        @PathVariable id: Long,
    ): SocialPostDto = posts.retry(id)

    @DeleteMapping("/posts/{id}")
    fun delete(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        posts.delete(id)
        return ResponseEntity.noContent().build()
    }

    // ---- Automation keys ----

    @GetMapping("/api-keys")
    fun apiKeys(): List<SocialApiKeyDto> = keys.list()

    @PostMapping("/api-keys")
    fun createApiKey(
        @RequestBody body: ApiKeyRequest,
    ): SocialApiKeyDto = keys.create(body.name, body.draftsOnly, actor().userId)

    @DeleteMapping("/api-keys/{id}")
    fun revokeApiKey(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        keys.revoke(id)
        return ResponseEntity.noContent().build()
    }

    // ---- AI daily posts ----

    @GetMapping("/ai")
    fun aiOverview(): SocialAiOverviewDto = ai.overview()

    @PutMapping("/ai/{site}")
    fun saveAiSetting(
        @PathVariable site: String,
        @RequestBody body: SocialAiSettingDto,
    ): SocialAiSettingDto = ai.saveSetting(site, body, actor().userId)

    /** "Napravi sada": today's AI post for this site now (once a day; a failed or skipped run can be repeated). */
    @PostMapping("/ai/{site}/run")
    fun runAi(
        @PathVariable site: String,
    ): SocialAiRunDto = ai.run(site)

    private fun actor(): SocialActor {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? UserDomainEntity
        return SocialActor(userId = principal?.userId, name = principal?.email, source = SocialPostSource.ADMIN)
    }
}
