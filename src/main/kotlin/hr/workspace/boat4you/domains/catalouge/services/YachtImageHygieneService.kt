package hr.workspace.boat4you.domains.catalouge.services

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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
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
 *   one at a time (bounded native memory), a page of [limit] rows after [afterId]; apply sets layout_override = true.
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

    fun detectLayout(
        dryRun: Boolean,
        onlyMain: Boolean,
        limit: Int,
        afterId: Long,
    ): LayoutScanDto {
        val pageSize = limit.coerceIn(1, MAX_LAYOUT_LIMIT)
        val page = rows(if (onlyMain) LAYOUT_MAIN_SQL else LAYOUT_ALL_SQL, afterId, pageSize)
        var scanned = 0
        var unreadable = 0
        val candidates = mutableListOf<LayoutCandidateDto>()
        page
            .filterNot { LayoutImageRules.isLayout(it.externalUrl, it.url) } // already a layout by its name
            .forEach { row ->
                val path = Paths.get(uploadDir, row.url)
                val score = if (Files.exists(path)) runCatching { LayoutDetector.score(path.toString()) }.getOrNull() else null
                if (score == null) {
                    unreadable++
                    return@forEach
                }
                scanned++
                if (score.layout) {
                    candidates +=
                        LayoutCandidateDto(
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
                }
            }
        val fixed =
            if (dryRun) {
                emptyList()
            } else {
                candidates.forEach { jdbc.update(LAYOUT_SQL, it.imageId) }
                repick(candidates.filter { it.main }.map { it.yachtId })
            }
        val next = if (page.size >= pageSize) page.last().id else null
        log.info(
            "Layout scan (dryRun={}, onlyMain={}, afterId={}): {} measured, {} unreadable, {} candidates {}, next {}",
            dryRun,
            onlyMain,
            afterId,
            scanned,
            unreadable,
            candidates.size,
            candidates.map { it.imageId },
            next,
        )
        return LayoutScanDto(dryRun, onlyMain, scanned, unreadable, next, candidates.sortedByDescending { it.score }, fixed)
    }

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

        private const val ROW_COLUMNS = """
            SELECT yi.id, yi.yacht_id, yi.url, yi.external_url, (COALESCE(yi.main_image, false) OR y.main_image_id = yi.id) AS main
            FROM yacht_image yi
            JOIN yacht y ON y.id = yi.yacht_id
            WHERE yi.id > ? AND NOT yi.hidden AND yi.url IS NOT NULL AND yi.url <> ''
        """

        /** Params: afterId, limit. */
        const val BLOCKED_MODEL_SQL = "$ROW_COLUMNS AND yi.external_url LIKE '%/yachtModel/%' ORDER BY yi.id LIMIT ?"
        const val BLOCKED_ALL_SQL = "$ROW_COLUMNS ORDER BY yi.id LIMIT ?"
        const val LAYOUT_MAIN_SQL =
            "$ROW_COLUMNS AND y.sys_active AND yi.layout_override IS NULL AND (yi.main_image OR y.main_image_id = yi.id) ORDER BY yi.id LIMIT ?"
        const val LAYOUT_ALL_SQL = "$ROW_COLUMNS AND y.sys_active AND yi.layout_override IS NULL ORDER BY yi.id LIMIT ?"

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
