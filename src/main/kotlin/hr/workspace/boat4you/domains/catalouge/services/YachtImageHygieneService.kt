package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.catalouge.dto.BlockedImageDto
import hr.workspace.boat4you.domains.catalouge.dto.BlockedImageScanDto
import hr.workspace.boat4you.domains.catalouge.dto.LayoutCandidateDto
import hr.workspace.boat4you.domains.catalouge.dto.LayoutScanDto
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageHygieneRequest
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.utils.KnownPlaceholderImages
import hr.workspace.boat4you.domains.catalouge.utils.LayoutDetector
import hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules
import hr.workspace.boat4you.domains.catalouge.utils.LayoutScore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Admin repairs of partner images (V9_91, owner incident 16.10.2026), SYSTEM_ADMIN endpoints of
 * AdminCatalogueImageController. Every scan is a dry run unless asked otherwise; an apply writes one row at a time
 * (autocommit, no lock held across the scan) and never touches a row a sync or an admin already decided (the UPDATEs
 * re-check). The card photo of a boat that changes follows through [YachtMainImageFixService.repickMain].
 *
 * - [detectBlocked]: stored files that are the partner's placeholder (KnownPlaceholderImages: exact byte size, then
 *   SHA-256 of the stored WebP) - by default only NauSys MODEL pictures (external_url .../yachtModel/...), where the
 *   blocked photo comes from; apply hides them (reason PARTNER_BLOCKED_PHOTO).
 * - [detectLayout]: images the file-name rule does not call a layout drawing, decoded and measured by [LayoutDetector]
 *   one at a time (bounded native memory): a dry run pages through [limit] rows after [afterId]; an apply takes only the
 *   reviewed image ids (the candidates an admin looked at) and sets layout_override = true on those the detector still
 *   flags - never on a row nobody reviewed (a re-scanned page slides past the PATCHed false positives).
 * - [update]: an admin's own call on one image (layout / hidden).
 */
@Service
class YachtImageHygieneService(
    private val jdbc: JdbcTemplate,
    private val mainImageFix: YachtMainImageFixService,
    @Value("\${application.upload.directory}")
    private val uploadDir: String,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val named = NamedParameterJdbcTemplate(jdbc)

    private class Row(
        val id: Long,
        val yachtId: Long,
        val url: String,
        val externalUrl: String?,
        val main: Boolean,
    )

    fun detectBlocked(
        dryRun: Boolean,
        onlyModelPictures: Boolean,
    ): BlockedImageScanDto {
        var scanned = 0
        var hashed = 0
        var missing = 0
        val blocked = mutableListOf<BlockedImageDto>()
        var afterId = 0L
        while (true) {
            val page = rows(if (onlyModelPictures) BLOCKED_MODEL_SQL else BLOCKED_ALL_SQL, afterId, PAGE_SIZE)
            page.forEach { row ->
                scanned++
                val path = Paths.get(uploadDir, row.url)
                if (!Files.exists(path)) {
                    missing++
                    return@forEach
                }
                if (Files.size(path) !in KnownPlaceholderImages.STORED_SIZES) return@forEach
                hashed++
                if (KnownPlaceholderImages.sha256(Files.readAllBytes(path)) in KnownPlaceholderImages.STORED_SHA256) {
                    blocked += BlockedImageDto(row.id, row.yachtId, row.main)
                }
            }
            if (page.size < PAGE_SIZE) break
            afterId = page.last().id
        }
        val fixed =
            if (dryRun) {
                emptyList()
            } else {
                blocked.forEach { jdbc.update(HIDE_SQL, KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO, it.imageId) }
                repick(blocked.map { it.yachtId })
            }
        log.info(
            "Blocked-photo scan (dryRun={}, onlyModelPictures={}): {} scanned, {} hashed, {} missing, {} placeholders on {} boats {}",
            dryRun,
            onlyModelPictures,
            scanned,
            hashed,
            missing,
            blocked.size,
            blocked.map { it.yachtId }.distinct().size,
            blocked.map { it.imageId },
        )
        return BlockedImageScanDto(dryRun, scanned, hashed, missing, blocked, fixed)
    }

    /**
     * A dry run without [imageIds] measures a page of [limit] rows after [afterId]. With [imageIds] (at most
     * [MAX_LAYOUT_LIMIT]) only those rows are measured - still under the same filter (active boat, visible, stored,
     * layout_override not set; a main image with [onlyMain]). An apply requires them: the candidates of the reviewed dry
     * run, without the false positives; ids no longer eligible or no longer flagged are listed as skipped.
     */
    fun detectLayout(
        dryRun: Boolean,
        onlyMain: Boolean,
        limit: Int,
        afterId: Long,
        imageIds: List<Long>? = null,
    ): LayoutScanDto {
        val reviewed = reviewedIds(dryRun, imageIds)
        val pageSize = limit.coerceIn(1, MAX_LAYOUT_LIMIT)
        val page =
            if (reviewed.isEmpty()) {
                rows(if (onlyMain) LAYOUT_MAIN_SQL else LAYOUT_ALL_SQL, afterId, pageSize)
            } else {
                named.query(if (onlyMain) LAYOUT_MAIN_IDS_SQL else LAYOUT_ALL_IDS_SQL, mapOf("ids" to reviewed), row)
            }
        val measured =
            page
                .filterNot { LayoutImageRules.isLayout(it.externalUrl, it.url) } // already a layout by its name
                .map { it to measure(it) }
        val unreadable = measured.count { it.second == null }
        val scanned = measured.size - unreadable
        val candidates = measured.mapNotNull { (image, score) -> score?.takeIf { it.layout }?.let { candidate(image, it) } }
        val fixed =
            if (dryRun) {
                emptyList()
            } else {
                candidates.forEach { jdbc.update(LAYOUT_SQL, it.imageId) }
                repick(candidates.filter { it.main }.map { it.yachtId })
            }
        // the reviewed ids are no page: nothing to continue from
        val next = if (reviewed.isEmpty() && page.size >= pageSize) page.last().id else null
        val skipped = reviewed - candidates.map { it.imageId }.toSet()
        log.info(
            "Layout scan (dryRun={}, onlyMain={}, afterId={}, reviewed={}): {} measured, {} unreadable, {} candidates {}, skipped {}, next {}",
            dryRun,
            onlyMain,
            afterId,
            reviewed.size,
            scanned,
            unreadable,
            candidates.size,
            candidates.map { it.imageId },
            skipped,
            next,
        )
        return LayoutScanDto(dryRun, onlyMain, scanned, unreadable, next, candidates.sortedByDescending { it.score }, fixed, skipped)
    }

    /** The reviewed image ids of a detect-layout call: required for an apply, at most [MAX_LAYOUT_LIMIT]. */
    private fun reviewedIds(
        dryRun: Boolean,
        imageIds: List<Long>?,
    ): List<Long> {
        val reviewed = imageIds.orEmpty().distinct()
        if (!dryRun && reviewed.isEmpty()) {
            throw ParameterValidationException(mapOf("imageIds" to "required with dryRun=false: the reviewed candidates of a dry run"))
        }
        if (reviewed.size > MAX_LAYOUT_LIMIT) {
            throw ParameterValidationException(mapOf("imageIds" to "at most $MAX_LAYOUT_LIMIT ids per call"))
        }
        return reviewed
    }

    /** null: the file is missing or cannot be decoded. */
    private fun measure(row: Row): LayoutScore? {
        val path = Paths.get(uploadDir, row.url)
        return if (Files.exists(path)) runCatching { LayoutDetector.score(path.toString()) }.getOrNull() else null
    }

    private fun candidate(
        row: Row,
        score: LayoutScore,
    ) = LayoutCandidateDto(
        imageId = row.id,
        yachtId = row.yachtId,
        main = row.main,
        score = score.score,
        whiteFraction = score.whiteFraction,
        backgroundFraction = score.backgroundFraction,
        contentBackgroundFraction = score.contentBackgroundFraction,
        meanSaturation = score.meanSaturation,
        edgeDensity = score.edgeDensity,
        darkComponents = score.darkComponents,
        width = score.width,
        height = score.height,
    )

    @Transactional
    fun update(
        imageId: Long,
        request: YachtImageHygieneRequest,
    ): YachtImageAdminDto {
        val yachtId =
            jdbc.query("SELECT yacht_id FROM yacht_image WHERE id = ?", { rs, _ -> rs.getLong(1) }, imageId).firstOrNull()
                ?: throw ImageNotFoundException()
        request.layout?.let { jdbc.update("UPDATE yacht_image SET layout_override = ? WHERE id = ?", it, imageId) }
        when (request.hidden) {
            true -> jdbc.update(HIDE_SQL, ADMIN, imageId)
            false -> jdbc.update("UPDATE yacht_image SET hidden = false, hidden_reason = NULL WHERE id = ?", imageId)
            null -> {}
        }
        if (request.layout != null || request.hidden != null) {
            mainImageFix.repickMain(yachtId)
            log.info("Admin image change {}: layout={}, hidden={} (yacht {})", imageId, request.layout, request.hidden, yachtId)
        }
        return jdbc.query(ADMIN_SQL, adminRow, imageId).first()
    }

    private val adminRow =
        RowMapper { rs, _ ->
            val override = rs.getBoolean("layout_override").takeUnless { rs.wasNull() }
            YachtImageAdminDto(
                id = rs.getLong("id"),
                yachtId = rs.getLong("yacht_id"),
                position = rs.getShort("position").takeUnless { rs.wasNull() },
                mainImage = rs.getBoolean("main_image").takeUnless { rs.wasNull() },
                layout = override ?: LayoutImageRules.isLayout(rs.getString("external_url"), rs.getString("url")),
                layoutOverride = override,
                hidden = rs.getBoolean("hidden"),
                hiddenReason = rs.getString("hidden_reason"),
                yachtMainImageId = rs.getLong("yacht_main_image_id").takeUnless { rs.wasNull() },
            )
        }

    private fun rows(
        sql: String,
        afterId: Long,
        limit: Int,
    ): List<Row> = jdbc.query(sql, row, afterId, limit)

    private val row =
        RowMapper { rs, _ ->
            Row(rs.getLong("id"), rs.getLong("yacht_id"), rs.getString("url"), rs.getString("external_url"), rs.getBoolean("main"))
        }

    private fun repick(yachtIds: List<Long>): List<MainImageFixDto> = yachtIds.distinct().mapNotNull { mainImageFix.repickMain(it) }

    companion object {
        const val ADMIN = "ADMIN"
        const val MAX_LAYOUT_LIMIT = 2000
        private const val PAGE_SIZE = 5000

        private const val ROW_SELECT = """
            SELECT yi.id, yi.yacht_id, yi.url, yi.external_url, (COALESCE(yi.main_image, false) OR y.main_image_id = yi.id) AS main
            FROM yacht_image yi
            JOIN yacht y ON y.id = yi.yacht_id
            WHERE NOT yi.hidden AND yi.url IS NOT NULL AND yi.url <> ''
        """
        private const val ROW_COLUMNS = "$ROW_SELECT AND yi.id > ?"
        private const val LAYOUT_FILTER = "AND y.sys_active AND yi.layout_override IS NULL"
        private const val MAIN_FILTER = "AND (yi.main_image OR y.main_image_id = yi.id)"

        /** Params: afterId, limit. */
        const val BLOCKED_MODEL_SQL = "$ROW_COLUMNS AND yi.external_url LIKE '%/yachtModel/%' ORDER BY yi.id LIMIT ?"
        const val BLOCKED_ALL_SQL = "$ROW_COLUMNS ORDER BY yi.id LIMIT ?"
        const val LAYOUT_MAIN_SQL = "$ROW_COLUMNS $LAYOUT_FILTER $MAIN_FILTER ORDER BY yi.id LIMIT ?"
        const val LAYOUT_ALL_SQL = "$ROW_COLUMNS $LAYOUT_FILTER ORDER BY yi.id LIMIT ?"

        /** Named param :ids - the reviewed candidates. */
        const val LAYOUT_MAIN_IDS_SQL = "$ROW_SELECT $LAYOUT_FILTER $MAIN_FILTER AND yi.id IN (:ids) ORDER BY yi.id"
        const val LAYOUT_ALL_IDS_SQL = "$ROW_SELECT $LAYOUT_FILTER AND yi.id IN (:ids) ORDER BY yi.id"

        private const val HIDE_SQL = "UPDATE yacht_image SET hidden = true, hidden_reason = ?, main_image = false WHERE id = ?"
        private const val LAYOUT_SQL = "UPDATE yacht_image SET layout_override = true WHERE id = ? AND layout_override IS NULL AND NOT hidden"
        private const val ADMIN_SQL = """
            SELECT yi.id, yi.yacht_id, yi."position", yi.main_image, yi.url, yi.external_url, yi.layout_override, yi.hidden,
                   yi.hidden_reason, y.main_image_id AS yacht_main_image_id
            FROM yacht_image yi
            JOIN yacht y ON y.id = yi.yacht_id
            WHERE yi.id = ?
        """
    }
}
