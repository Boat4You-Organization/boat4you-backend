package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.YachtLookupDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtMediaDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.utils.VideoUrlRules
import hr.workspace.boat4you.domains.catalouge.utils.YachtLabel
import hr.workspace.boat4you.domains.catalouge.utils.YachtPublicSlug
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Types

/** The public URL of a boat's crew profile PDF (V9_92): `{api}/public/yachts/{id}/crew-profile.pdf`. */
fun crewProfileUrl(
    apiHost: String,
    yachtId: Long,
): String = "${apiHost.trimEnd('/')}/public/yachts/$yachtId/crew-profile.pdf"

/** The stored crew profile of a boat, ready to stream. */
class CrewProfileFile(
    val path: Path,
    /** "<Manufacturer Model Name> - Crew profile.pdf". */
    val fileName: String,
)

/** DELETE /admin/yachts/{id}/images/{imageId} on a partner picture: only our own photos are deleted here (409). */
class PartnerImageNotDeletableException : RuntimeException()

/**
 * Our own media on any boat (V9_92, owner request 17.10.2026: gulet Allure 11769, a partner-synced EXTERNAL boat, gets our
 * photos, its film and the crew profile on all 7 sites) - the SYSTEM_ADMIN calls of AdminYachtMediaController and the
 * public crew profile PDF. Plain SQL like YachtImageHygieneService: the boat row is never re-saved through Hibernate, so
 * an admin call never writes back stale partner data, and the partner syncs never write these columns (read-only for
 * Hibernate). Every write locks the boat row first, so two admin calls on one boat queue instead of mixing positions.
 *
 * - Photos: stored like every upload (FileSystemService.saveImage: JPEG / PNG / WebP by magic bytes, 10 MB each, WebP
 *   `y-{id}/{uuid}.webp`) as yacht_image rows with source OWN, synced (nothing to download), at most [MAX_IMAGES] per
 *   request, all or none. Positions: OWN first, the partner's pictures after them - the order the partner syncs keep
 *   (OwnImageRules). The main-image upload puts its photo first and makes it the card photo; an OWN main survives every
 *   sync (MainImageRules). Deleting is for OWN rows only: a partner picture comes back with the next sync (409).
 * - Film: yacht.video_url, YouTube / Vimeo only ([VideoUrlRules]).
 * - Crew profile: one PDF per boat, `documents/y-{id}/{uuid}.pdf` (FileSystemService.savePdfFile: %PDF magic bytes,
 *   [MAX_PDF_MB] MB); a replaced or removed file is deleted once the change is committed.
 * The custom-boat endpoints (/admin/custom-yachts, YachtMutationService) are unchanged.
 */
@Service
class YachtOwnMediaService(
    private val jdbc: JdbcTemplate,
    private val fileSystemService: FileSystemService,
    private val mainImageFix: YachtMainImageFixService,
    /** The API host that serves `/public/...`: the crew profile URL is absolute. */
    @Value("\${server.host}") private val serverHost: String,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val named = NamedParameterJdbcTemplate(jdbc)

    private class YachtRow(
        val mainImageId: Long?,
        val videoUrl: String?,
        val crewProfileUrl: String?,
    )

    private class ImageRow(
        val id: Long,
        val position: Short?,
        val own: Boolean,
        val mainImage: Boolean?,
        val url: String?,
    )

    @Transactional(readOnly = true)
    fun media(yachtId: Long): YachtMediaDto = dto(yachtId, yacht(yachtId, lock = false))

    /** POST /admin/yachts/{id}/images: after our photos already there, before the partner's. */
    @Transactional
    fun addImages(
        yachtId: Long,
        files: List<MultipartFile>?,
    ): YachtMediaDto {
        yacht(yachtId, lock = false)
        val stored = store(yachtId, requireFiles(files))
        return withCleanup(stored) {
            yacht(yachtId, lock = true)
            val ids = stored.map { insertOwn(yachtId, it) }
            renumber(yachtId, first = emptyList(), newOwn = ids)
            log.info("Own photos {} added to yacht {}", ids, yachtId)
            dto(yachtId, yacht(yachtId, lock = false))
        }
    }

    /** POST /admin/yachts/{id}/main-image: the photo goes first and becomes the card photo (yacht.main_image_id + flag). */
    @Transactional
    fun addMainImage(
        yachtId: Long,
        file: MultipartFile?,
    ): YachtMediaDto {
        if (file == null || file.isEmpty) throw ParameterValidationException(mapOf("file" to "an image file is required"))
        yacht(yachtId, lock = false)
        val stored = store(yachtId, listOf(file), field = { "file" })
        return withCleanup(stored) {
            yacht(yachtId, lock = true)
            val id = insertOwn(yachtId, stored.single())
            renumber(yachtId, first = listOf(id), newOwn = emptyList())
            mainImageFix.setMain(yachtId, id)
            log.info("Own main photo {} added to yacht {}", id, yachtId)
            dto(yachtId, yacht(yachtId, lock = false))
        }
    }

    /** PUT /admin/yachts/{id}/images/order: [imageIds] first in that order, the rest after them in today's order. */
    @Transactional
    fun reorder(
        yachtId: Long,
        imageIds: List<Long>?,
    ): YachtMediaDto {
        yacht(yachtId, lock = true)
        val images = images(yachtId)
        val known = images.map { it.id }.toSet()
        val ids = imageIds.orEmpty()
        when {
            ids.isEmpty() -> throw ParameterValidationException(mapOf("imageIds" to "at least one image id required"))
            ids.size != ids.distinct().size -> throw ParameterValidationException(mapOf("imageIds" to "an image id is listed twice"))
            !known.containsAll(ids) -> throw ParameterValidationException(mapOf("imageIds" to "not images of yacht $yachtId: ${ids - known}"))
        }
        val listed = ids.toSet()
        val rest = images.sortedWith(BY_POSITION).map { it.id }.filterNot { it in listed }
        writePositions(images, ids + rest)
        log.info("Images of yacht {} reordered: {} first", yachtId, ids)
        return dto(yachtId, yacht(yachtId, lock = false))
    }

    /** DELETE /admin/yachts/{id}/images/{imageId}: our own photo only; the card photo moves on when it was this one. */
    @Transactional
    fun deleteImage(
        yachtId: Long,
        imageId: Long,
    ): YachtMediaDto {
        val yacht = yacht(yachtId, lock = true)
        val image = images(yachtId).firstOrNull { it.id == imageId } ?: throw ImageNotFoundException()
        if (!image.own) throw PartnerImageNotDeletableException()
        jdbc.update("DELETE FROM yacht_image WHERE id = ? AND yacht_id = ?", imageId, yachtId)
        image.url?.takeIf { it.isNotBlank() }?.let(::deleteFileAfterCommit)
        if (yacht.mainImageId == imageId || image.mainImage == true) {
            mainImageFix.repickMain(yachtId)
            mainImageFix.flagMain(yachtId, yacht(yachtId, lock = false).mainImageId)
        }
        log.info("Own photo {} of yacht {} deleted", imageId, yachtId)
        return dto(yachtId, yacht(yachtId, lock = false))
    }

    /** PATCH /admin/yachts/{id}/media: the film URL ([VideoUrlRules]); null or blank removes it. */
    @Transactional
    fun setVideo(
        yachtId: Long,
        videoUrl: String?,
    ): YachtMediaDto {
        val url = videoUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null && !VideoUrlRules.isAccepted(url)) {
            throw ParameterValidationException(
                mapOf(
                    "videoUrl" to
                        "a YouTube (youtube.com/watch?v=..., youtu.be/..., youtube.com/embed/...) or Vimeo (vimeo.com/<id>, " +
                        "player.vimeo.com/video/<id>) URL of at most ${VideoUrlRules.MAX_LENGTH} characters",
                ),
            )
        }
        yacht(yachtId, lock = true)
        jdbc.update("UPDATE yacht SET video_url = ? WHERE id = ?", url, yachtId)
        log.info("Video of yacht {} set to {}", yachtId, url)
        return dto(yachtId, yacht(yachtId, lock = false))
    }

    /** POST /admin/yachts/{id}/crew-profile: one PDF per boat; the previous file is deleted after the commit. */
    @Transactional
    fun setCrewProfile(
        yachtId: Long,
        file: MultipartFile?,
    ): YachtMediaDto {
        if (file == null || file.isEmpty) throw ParameterValidationException(mapOf("file" to "a PDF file is required"))
        if (file.size > MAX_PDF_MB * BYTES_PER_MB) throw ParameterValidationException(mapOf("file" to "at most $MAX_PDF_MB MB"))
        yacht(yachtId, lock = false)
        val path =
            fileSystemService
                .savePdfFile(file, crewProfileDir(yachtId), MAX_PDF_MB)
                .getOrElse { throw ParameterValidationException(mapOf("file" to (it.message ?: "not a PDF file"))) }
        return withCleanup(listOf(path)) {
            val old = yacht(yachtId, lock = true).crewProfileUrl
            jdbc.update("UPDATE yacht SET crew_profile_url = ? WHERE id = ?", path, yachtId)
            old?.takeIf { it.isNotBlank() && it != path }?.let(::deleteFileAfterCommit)
            log.info("Crew profile of yacht {} stored as {} (was {})", yachtId, path, old)
            dto(yachtId, yacht(yachtId, lock = false))
        }
    }

    /** DELETE /admin/yachts/{id}/crew-profile: the file goes after the commit; no profile = nothing to do. */
    @Transactional
    fun deleteCrewProfile(yachtId: Long): YachtMediaDto {
        val old = yacht(yachtId, lock = true).crewProfileUrl
        if (old != null) {
            jdbc.update("UPDATE yacht SET crew_profile_url = NULL WHERE id = ?", yachtId)
            old.takeIf { it.isNotBlank() }?.let(::deleteFileAfterCommit)
            log.info("Crew profile {} of yacht {} removed", old, yachtId)
        }
        return dto(yachtId, yacht(yachtId, lock = false))
    }

    /**
     * GET /public/yachts/{id}/crew-profile.pdf: the stored file of an active boat, or null (404). Only the path stored for
     * this boat is ever opened, and only inside its own `documents/y-{id}/` folder of the upload dir.
     */
    @Transactional(readOnly = true)
    fun crewProfile(yachtId: Long): CrewProfileFile? {
        val folder = fileSystemService.getResourcePath(crewProfileDir(yachtId)).toAbsolutePath().normalize()
        return jdbc
            .query(CREW_PROFILE_SQL, { rs, _ ->
                val stored = rs.getString("crew_profile_url")?.takeIf { it.isNotBlank() }
                val path = stored?.let { fileSystemService.getResourcePath(it).toAbsolutePath().normalize() }
                val usable = path != null && path.startsWith(folder) && Files.isRegularFile(path)
                if (stored != null && !usable) log.warn("Crew profile of yacht {}: stored path {} is not a file in {}", yachtId, stored, folder)
                path?.takeIf { usable }?.let {
                    CrewProfileFile(it, crewProfileFileName(rs.getString("manufacturer_name"), rs.getString("model_name"), rs.getString("name")))
                }
            }, yachtId)
            .firstOrNull()
    }

    /** GET /admin/yachts/lookup: boats by id or (public or partner) name, the exact id first, then active boats by name. */
    @Transactional(readOnly = true)
    fun lookup(
        q: String?,
        limit: Int,
    ): List<YachtLookupDto> {
        val query = q?.trim().orEmpty()
        if (query.isEmpty()) return emptyList()
        val params =
            MapSqlParameterSource()
                .addValue("id", query.toLongOrNull(), Types.BIGINT)
                .addValue("pattern", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", Types.VARCHAR)
                .addValue("limit", limit.coerceIn(1, MAX_LOOKUP), Types.INTEGER)
        return named.query(LOOKUP_SQL, params) { rs, _ ->
            val id = rs.getLong("id")
            val manufacturer = rs.getString("manufacturer_name")
            val model = rs.getString("model_name")
            val name = rs.getString("name")
            YachtLookupDto(
                id = id,
                name = name,
                manufacturerName = manufacturer,
                modelName = model,
                slug = YachtPublicSlug.of(rs.getString("public_slug"), manufacturer, model, name, id),
                entryType = rs.getString("entry_type")?.let { v -> EntryType.entries.firstOrNull { it.name == v } },
                vesselType = rs.getString("vessel_type")?.let { v -> VesselType.entries.firstOrNull { it.name == v } },
                mainImageId = rs.getLong("main_image_id").takeUnless { rs.wasNull() },
                imageCount = rs.getInt("image_count"),
                hasVideo = rs.getBoolean("has_video"),
                hasCrewProfile = rs.getBoolean("has_crew_profile"),
            )
        }
    }

    private fun requireFiles(files: List<MultipartFile>?): List<MultipartFile> {
        if (files.isNullOrEmpty()) throw ParameterValidationException(mapOf("files" to "at least one image required"))
        if (files.size > MAX_IMAGES) throw ParameterValidationException(mapOf("files" to "at most $MAX_IMAGES images per request"))
        return files
    }

    /** The boat's row ([lock]: FOR UPDATE, the per-boat queue of the writes); an unknown boat answers 404. */
    private fun yacht(
        yachtId: Long,
        lock: Boolean,
    ): YachtRow =
        jdbc
            .query(
                "SELECT main_image_id, video_url, crew_profile_url FROM yacht WHERE id = ?" + if (lock) " FOR UPDATE" else "",
                { rs, _ ->
                    YachtRow(rs.getLong("main_image_id").takeUnless { rs.wasNull() }, rs.getString("video_url"), rs.getString("crew_profile_url"))
                },
                yachtId,
            ).firstOrNull() ?: throw YachtDoesNotExistException()

    private fun images(yachtId: Long): List<ImageRow> =
        jdbc.query(
            "SELECT id, \"position\", source, main_image, url FROM yacht_image WHERE yacht_id = ?",
            { rs, _ ->
                ImageRow(
                    id = rs.getLong("id"),
                    position = rs.getShort("position").takeUnless { rs.wasNull() },
                    own = rs.getString("source") == YachtImageSource.OWN.name,
                    mainImage = rs.getBoolean("main_image").takeUnless { rs.wasNull() },
                    url = rs.getString("url"),
                )
            },
            yachtId,
        )

    /** Converts and writes every file before any row is written; one bad file removes the ones already written (400). */
    @Suppress("TooGenericExceptionCaught") // any failure removes the files this request wrote, then rethrows
    private fun store(
        yachtId: Long,
        files: List<MultipartFile>,
        field: (Int) -> String = { "files[$it]" },
    ): List<String> {
        val written = mutableListOf<String>()
        try {
            files.forEachIndexed { i, file ->
                written +=
                    fileSystemService
                        .saveImage(file, "y-$yachtId")
                        .getOrElse { throw ParameterValidationException(mapOf(field(i) to (it.message ?: "not a valid image"))) }
            }
            return written
        } catch (e: Exception) {
            written.forEach { fileSystemService.deleteFile(it) }
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed write removes the files of this request, then rethrows (the rows roll back)
    private fun <T> withCleanup(
        files: List<String>,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            files.forEach { fileSystemService.deleteFile(it) }
            throw e
        }

    private fun insertOwn(
        yachtId: Long,
        url: String,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO yacht_image (url, external_url, \"position\", yacht_id, main_image, synced, source) " +
                "VALUES (?, NULL, NULL, ?, false, true, 'OWN') RETURNING id",
            Long::class.java,
            url,
            yachtId,
        )!!

    /** [first], then our photos already there, then [newOwn], then the partner's pictures - each group in today's order. */
    private fun renumber(
        yachtId: Long,
        first: List<Long>,
        newOwn: List<Long>,
    ) {
        val images = images(yachtId)
        val placed = (first + newOwn).toSet()
        val (own, partner) = images.filterNot { it.id in placed }.sortedWith(BY_POSITION).partition { it.own }
        writePositions(images, first + own.map { it.id } + newOwn + partner.map { it.id })
    }

    /** Positions 0, 1, ... in [order]; only the rows whose position changes are written. */
    private fun writePositions(
        images: List<ImageRow>,
        order: List<Long>,
    ) {
        val current = images.associate { it.id to it.position }
        order.forEachIndexed { index, id ->
            if (current[id]?.toInt() != index) jdbc.update("UPDATE yacht_image SET \"position\" = ? WHERE id = ?", index.toShort(), id)
        }
    }

    /** After the commit (a rollback keeps the file the row still names); right away outside a transaction. */
    private fun deleteFileAfterCommit(path: String) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        fileSystemService.deleteFile(path)
                    }
                },
            )
        } else {
            fileSystemService.deleteFile(path)
        }
    }

    private fun dto(
        yachtId: Long,
        yacht: YachtRow,
    ) = YachtMediaDto(
        yachtId = yachtId,
        mainImageId = yacht.mainImageId,
        videoUrl = yacht.videoUrl,
        crewProfileUrl = yacht.crewProfileUrl?.takeIf { it.isNotBlank() }?.let { crewProfileUrl(serverHost, yachtId) },
        images = jdbc.query(ADMIN_BY_YACHT_SQL, YachtImageHygieneService.ADMIN_ROW, yachtId),
    )

    companion object {
        const val MAX_IMAGES = 20
        const val MAX_PDF_MB = 15L
        const val MAX_LOOKUP = 50
        private const val BYTES_PER_MB = 1024L * 1024L

        private val BY_POSITION = compareBy<ImageRow, Short?>(nullsLast()) { it.position }.thenBy { it.id }

        fun crewProfileDir(yachtId: Long) = "documents/y-$yachtId"

        /** "<Manufacturer Model Name> - Crew profile.pdf" (the name left out when it only repeats the model, R31). */
        fun crewProfileFileName(
            manufacturer: String?,
            model: String?,
            name: String?,
        ): String {
            val label =
                listOfNotNull(manufacturer, model, name?.takeUnless { YachtLabel.nameRepeatsModel(model, it) })
                    .map { it.replace(UNSAFE_FILE_NAME_CHARS, " ").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .ifEmpty { "Yacht" }
            return "$label - Crew profile.pdf"
        }

        private val UNSAFE_FILE_NAME_CHARS = Regex("[\\p{Cntrl}\"\\\\/:*?<>|]+")

        private val ADMIN_BY_YACHT_SQL = "${YachtImageHygieneService.ADMIN_COLUMNS} WHERE yi.yacht_id = ? ORDER BY yi.\"position\" NULLS LAST, yi.id"

        private const val CREW_PROFILE_SQL = """
            SELECT y.crew_profile_url, y.name, m.name AS model_name, mf.name AS manufacturer_name
            FROM yacht y
            LEFT JOIN model m ON m.id = y.model_id
            LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            WHERE y.id = ? AND y.sys_active
        """

        private const val LOOKUP_SQL = """
            SELECT y.id, y.name, mf.name AS manufacturer_name, m.name AS model_name, y.public_slug, y.entry_type, y.vessel_type,
                   y.main_image_id,
                   (SELECT count(*) FROM yacht_image yi
                    WHERE yi.yacht_id = y.id AND NOT yi.hidden AND yi.url IS NOT NULL AND yi.url <> '') AS image_count,
                   (NULLIF(y.video_url, '') IS NOT NULL
                    OR EXISTS (SELECT 1 FROM custom_yacht_details c WHERE c.yacht_id = y.id AND NULLIF(c.video_url, '') IS NOT NULL)) AS has_video,
                   (NULLIF(y.crew_profile_url, '') IS NOT NULL) AS has_crew_profile
            FROM yacht y
            LEFT JOIN model m ON m.id = y.model_id
            LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            WHERE y.id = :id OR y.name ILIKE :pattern OR y.partner_name ILIKE :pattern
            ORDER BY (y.id IS NOT DISTINCT FROM :id) DESC, y.sys_active DESC, y.name, y.id
            LIMIT :limit
        """
    }
}
