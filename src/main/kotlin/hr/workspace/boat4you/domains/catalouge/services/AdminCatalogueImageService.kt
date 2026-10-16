package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageFromUrlRequest
import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageUpdateRequest
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.util.Locale

/**
 * Marina photos for the boat gallery (design 16.10.2026), held in catalogue_image - never in yacht_image, whose rows and
 * files the partner syncs delete. Upload (multipart) and server-side import from URLs (the lead's scraped set) both go
 * through FileSystemService.saveImage: magic bytes JPEG / PNG / WebP, the 10 MB cap, stored as `marina-{id}/{uuid}.webp`.
 * Every photo carries a credit (shown with it on the site) and a source URL (takedown trail). A request stores all of
 * its photos or none: on any failure the files already written are deleted again and the rows roll back.
 * A replaced photo must get a new id - /public/image/{id} is cached immutable for a year (Bunny + browsers), so a
 * deleted or taken-down photo stays visible there until the CDN is purged for it.
 */
@Service
class AdminCatalogueImageService(
    private val repository: CatalogueImageRepository,
    private val locationRepository: LocationRepository,
    private val fileSystemService: FileSystemService,
    private val fetcher: RemoteImageFetcher,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    private class Meta(
        val caption: String?,
        val credit: String?,
        val sourceUrl: String?,
        val licence: String?,
    )

    @Transactional(readOnly = true)
    fun list(locationId: Long): List<CatalogueImageAdminDto> {
        requireLocation(locationId)
        return repository.findByLocationIdOrderByPositionAscIdAsc(locationId).map { it.toAdminDto() }
    }

    @Transactional
    @Suppress("LongParameterList") // the multipart form fields
    fun upload(
        locationId: Long,
        files: List<MultipartFile>?,
        caption: String?,
        credit: String?,
        sourceUrl: String?,
        licence: String?,
    ): List<CatalogueImageAdminDto> {
        requireLocation(locationId)
        val meta = Meta(caption.clean(), credit.clean(), sourceUrl.clean(), licence.clean())
        val errors = validate(meta, "")
        when {
            files.isNullOrEmpty() -> errors["files"] = "at least one image required"
            files.size > MAX_PER_REQUEST -> errors["files"] = "at most $MAX_PER_REQUEST images per request"
        }
        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        return store(locationId, files!!.mapIndexed { i, file -> Pending("files[$i]", meta) { fileSystemService.saveImage(file, subpath(locationId)) } })
    }

    /** Not @Transactional: the downloads (up to 10 x 15 s) must not hold a DB connection; only [store] runs in one. */
    fun importFromUrls(
        locationId: Long,
        request: CatalogueImageFromUrlRequest,
    ): List<CatalogueImageAdminDto> {
        requireLocation(locationId)
        val items = request.urls.orEmpty()
        val errors = mutableMapOf<String, String>()
        when {
            items.isEmpty() -> errors["urls"] = "at least one url required"
            items.size > MAX_PER_REQUEST -> errors["urls"] = "at most $MAX_PER_REQUEST urls per request"
        }
        val metas =
            items.mapIndexed { i, item ->
                val url = item.url.clean()
                if (url == null) {
                    errors["urls[$i].url"] = "required"
                } else if (!isHttpUrl(url) || url.length > SOURCE_URL_MAX) {
                    errors["urls[$i].url"] = "must be an http(s) URL of at most $SOURCE_URL_MAX characters"
                }
                val sourceUrl = item.sourceUrl.clean() ?: url?.takeIf { "urls[$i].url" !in errors }
                Meta(item.caption.clean(), item.credit.clean(), sourceUrl, item.licence.clean())
                    .also { errors.putAll(validate(it, "urls[$i].")) }
            }
        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        // Download everything first (no file written yet), so one bad URL fails the request before anything is stored.
        val downloaded =
            items.mapIndexed { i, item ->
                runCatching { fetcher.fetch(item.url!!.trim()) }
                    .onFailure { errors["urls[$i].url"] = it.message ?: "download failed" }
                    .getOrNull()
            }
        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        val pending = downloaded.mapIndexed { i, bytes -> Pending("urls[$i].url", metas[i]) { fileSystemService.saveImage(bytes!!, subpath(locationId)) } }
        return transactionTemplate.execute { store(locationId, pending) }!!
    }

    @Transactional
    fun update(
        id: Long,
        request: CatalogueImageUpdateRequest,
    ): CatalogueImageAdminDto {
        val image = repository.findById(id).orElseThrow { ImageNotFoundException() }
        val errors = mutableMapOf<String, String>()
        request.position?.let { if (it < 0) errors["position"] = "must be 0 or more" }
        request.caption?.let { if (it.trim().length > TEXT_MAX) errors["caption"] = "at most $TEXT_MAX characters" }
        request.credit?.let {
            when {
                it.isBlank() -> errors["credit"] = "must not be blank"
                it.trim().length > TEXT_MAX -> errors["credit"] = "at most $TEXT_MAX characters"
            }
        }
        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        request.position?.let { image.position = it }
        request.active?.let { image.active = it }
        request.caption?.let { image.caption = it.clean() }
        request.credit?.let { image.credit = it.trim() }
        return repository.saveAndFlush(image).toAdminDto()
    }

    @Transactional
    fun delete(id: Long) {
        val image = repository.findById(id).orElseThrow { ImageNotFoundException() }
        repository.delete(image)
        repository.flush()
        if (!fileSystemService.deleteFile(image.url!!)) {
            log.warn("Catalogue image {}: row deleted, file {} was not on disk", id, image.url)
        }
    }

    private class Pending(
        val field: String,
        val meta: Meta,
        val save: () -> Result<String>,
    )

    @Suppress("TooGenericExceptionCaught") // any failure removes the files this request wrote, then rethrows
    private fun store(
        locationId: Long,
        pending: List<Pending>,
    ): List<CatalogueImageAdminDto> {
        val createdBy = SecurityContextHolder.getContext().authentication?.name?.take(CREATED_BY_MAX)
        var position = (repository.findByLocationIdOrderByPositionAscIdAsc(locationId).maxOfOrNull { it.position.toInt() } ?: -1) + 1
        val written = mutableListOf<String>()
        try {
            val rows =
                pending.map { p ->
                    val path =
                        p.save().getOrElse { throw ParameterValidationException(mapOf(p.field to (it.message ?: "not a valid image"))) }
                    written += path
                    repository.save(
                        CatalogueImage().apply {
                            kind = CatalogueImage.KIND_MARINA
                            this.locationId = locationId
                            url = path
                            caption = p.meta.caption
                            credit = p.meta.credit
                            sourceUrl = p.meta.sourceUrl
                            licence = p.meta.licence
                            this.position = (position++).toShort()
                            this.createdBy = createdBy
                        },
                    )
                }
            repository.flush()
            log.info("Catalogue images {} stored for location {} by {}", rows.map { it.id }, locationId, createdBy)
            return rows.map { it.toAdminDto() }
        } catch (e: Exception) {
            written.forEach { fileSystemService.deleteFile(it) }
            throw e
        }
    }

    private fun validate(
        meta: Meta,
        prefix: String,
    ): MutableMap<String, String> {
        val errors = mutableMapOf<String, String>()
        if (meta.credit == null) errors["${prefix}credit"] = "required (e.g. \"Foto: Marina Kaštela\")"
        meta.credit?.let { if (it.length > TEXT_MAX) errors["${prefix}credit"] = "at most $TEXT_MAX characters" }
        meta.caption?.let { if (it.length > TEXT_MAX) errors["${prefix}caption"] = "at most $TEXT_MAX characters" }
        meta.licence?.let { if (it.length > LICENCE_MAX) errors["${prefix}licence"] = "at most $LICENCE_MAX characters" }
        // Rendered as a link next to the photo on the sites: http(s) only, never javascript: or data:.
        meta.sourceUrl?.let {
            if (!isHttpUrl(it) || it.length > SOURCE_URL_MAX) {
                errors["${prefix}sourceUrl"] = "must be an http(s) URL of at most $SOURCE_URL_MAX characters"
            }
        }
        return errors
    }

    private fun requireLocation(locationId: Long) {
        if (!locationRepository.existsById(locationId)) throw ResourceNotFound()
    }

    private fun subpath(locationId: Long) = "marina-$locationId"

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun isHttpUrl(value: String): Boolean =
        runCatching { URI(value) }
            .map { it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !it.host.isNullOrBlank() }
            .getOrDefault(false)

    companion object {
        const val MAX_PER_REQUEST = 10
        private const val TEXT_MAX = 255
        private const val SOURCE_URL_MAX = 1024
        private const val LICENCE_MAX = 64
        private const val CREATED_BY_MAX = 128
    }
}

private fun CatalogueImage.toAdminDto() =
    CatalogueImageAdminDto(
        id = id!!,
        kind = kind,
        locationId = locationId,
        url = url!!,
        caption = caption,
        credit = credit,
        sourceUrl = sourceUrl,
        licence = licence,
        position = position,
        active = active,
        created = created,
        createdBy = createdBy,
    )
