package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixDto
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixResultDto
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.utils.MainImageRules
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Types

/**
 * One-off repair behind POST /admin/yachts/main-image/fix-layout (16.10.2026: 11 offered boats showed their layout drawing
 * on the search card although they have photos; the same day the partner's "Picture is not shown due to possible legal
 * issues" placeholder on FuFu 4345 and 10 more boats). For every active boat whose main_image_id is
 * - a layout drawing ([MainImageRules.isLayout]: the row's layout_override, else the file name) and that has a stored
 *   photo: main_image_id becomes the photo the fixed sync would pick ([MainImageRules.pick]: the partner's main photo,
 *   else the lowest position), so the next sync keeps it; a boat with no photo is left alone and listed;
 * - a hidden image (V9_91) or an image that no longer exists: main_image_id becomes the pick among the boat's visible
 *   stored images - a photo, else a layout drawing, else NULL (the sites show their default picture, never the
 *   placeholder).
 * Only stored images (url set) qualify - the card serves the file. Each UPDATE re-checks the old value, so a sync that
 * moved the main meanwhile wins. The yacht_content_modified trigger (V9_71) moves the lastmod of the boats changed; the
 * cards follow at the next yacht_search_view refresh.
 */
@Service
class YachtMainImageFixService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val named = NamedParameterJdbcTemplate(jdbc)

    private class Main(
        val yachtId: Long,
        val mainImageId: Long,
        /** null: main_image_id names no image of the boat. */
        val image: YachtImage?,
    ) {
        val unusable: Boolean get() = image == null || MainImageRules.isHidden(image)
    }

    @Transactional
    fun fixLayoutMainImages(dryRun: Boolean): MainImageFixResultDto {
        val wrongMains =
            jdbc
                .query(MAINS_SQL) { rs, _ -> Main(rs.getLong("yacht_id"), rs.getLong("main_image_id"), image(rs)) }
                .filter { it.unusable || MainImageRules.isLayout(it.image!!) }
        if (wrongMains.isEmpty()) return MainImageFixResultDto(dryRun, 0, emptyList(), emptyList())

        val storedByYacht =
            named
                .query(IMAGES_SQL, mapOf("ids" to wrongMains.map { it.yachtId })) { rs, _ -> rs.getLong("yacht_id") to image(rs)!! }
                .groupBy({ it.first }, { it.second })
        val planned = mutableListOf<MainImageFixDto>()
        val withoutPhoto = mutableListOf<Long>()
        wrongMains.forEach { main ->
            // pick never returns a hidden image; for a layout main only a photo is better than what the card has
            val pick = MainImageRules.pick(storedByYacht[main.yachtId].orEmpty(), main.mainImageId)
            val target = if (main.unusable) pick else pick?.takeUnless(MainImageRules::isLayout)
            when {
                main.unusable && target?.id != main.mainImageId -> planned += MainImageFixDto(main.yachtId, main.mainImageId, target?.id)
                target == null -> withoutPhoto += main.yachtId
                else -> planned += MainImageFixDto(main.yachtId, main.mainImageId, target.id)
            }
        }
        val fixed =
            if (dryRun) {
                planned
            } else {
                planned.filter { jdbc.update(UPDATE_SQL, arrayOf(it.toImageId, it.yachtId, it.fromImageId), UPDATE_TYPES) == 1 }
            }
        val hidden = wrongMains.count { it.image != null && MainImageRules.isHidden(it.image) }
        val missing = wrongMains.count { it.image == null }
        log.info(
            "Main image fix (dryRun={}): {} boats with a layout, {} with a hidden, {} with a missing main; {} {} {}, {} without a photo",
            dryRun,
            wrongMains.size - hidden - missing,
            hidden,
            missing,
            fixed.size,
            if (dryRun) "to fix" else "fixed",
            fixed.map { it.yachtId },
            withoutPhoto.size,
        )
        return MainImageFixResultDto(dryRun, wrongMains.size - hidden - missing, fixed, withoutPhoto, hidden, missing)
    }

    /**
     * One boat after an admin decision (PATCH /admin/yacht-images/{id}, detect-blocked / detect-layout apply): its card
     * photo becomes [MainImageRules.pick] among its visible stored images - what its next partner sync would write.
     * Null when it stays (or a sync moved it meanwhile: the UPDATE re-checks the old value).
     */
    @Transactional
    fun repickMain(yachtId: Long): MainImageFixDto? {
        val current =
            jdbc.query("SELECT main_image_id FROM yacht WHERE id = ?", { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }, yachtId)
        if (current.isEmpty()) return null
        val stored = named.query(IMAGES_SQL, mapOf("ids" to listOf(yachtId))) { rs, _ -> image(rs)!! }
        val fix = MainImageFixDto(yachtId, current[0], MainImageRules.pick(stored, current[0])?.id)
        val changed = fix.toImageId != fix.fromImageId && jdbc.update(REPICK_SQL, arrayOf(fix.toImageId, yachtId, fix.fromImageId), UPDATE_TYPES) == 1
        return fix.takeIf { changed }
    }

    /**
     * An admin's own choice (V9_92: PATCH /admin/yacht-images/{id} {"main": true}, the main-image upload): the card photo
     * becomes [imageId] and exactly that image carries the main flag ([MainImageRules.flagMain] in SQL). The caller has
     * checked the image (a visible, stored photo of this boat).
     */
    @Transactional
    fun setMain(
        yachtId: Long,
        imageId: Long,
    ) {
        jdbc.update("UPDATE yacht SET main_image_id = ? WHERE id = ?", imageId, yachtId)
        flagMain(yachtId, imageId)
    }

    /** Exactly [mainId] carries yacht_image.main_image among the boat's images (none when null); a NULL flag stays NULL. */
    fun flagMain(
        yachtId: Long,
        mainId: Long?,
    ) {
        jdbc.update(FLAG_SQL, arrayOf(mainId, yachtId, mainId), UPDATE_TYPES)
    }

    /** null when the row has no image (main_image_id names no image of the boat: LEFT JOIN). */
    private fun image(rs: ResultSet): YachtImage? {
        val id = rs.getLong("image_id")
        if (rs.wasNull()) return null
        return YachtImage().apply {
            this.id = id
            url = rs.getString("url")
            externalUrl = rs.getString("external_url")
            position = rs.getShort("position").takeUnless { rs.wasNull() }
            mainImage = rs.getBoolean("main_image").takeUnless { rs.wasNull() }
            hidden = rs.getBoolean("hidden")
            layoutOverride = rs.getBoolean("layout_override").takeUnless { rs.wasNull() }
        }
    }

    companion object {
        private const val MAINS_SQL = """
            SELECT y.id AS yacht_id, y.main_image_id, yi.id AS image_id, yi.url, yi.external_url, yi."position", yi.main_image,
                   yi.hidden, yi.layout_override
            FROM yacht y
            LEFT JOIN yacht_image yi ON yi.id = y.main_image_id AND yi.yacht_id = y.id
            WHERE y.sys_active AND y.main_image_id IS NOT NULL
            ORDER BY y.id
        """
        private const val IMAGES_SQL = """
            SELECT yi.yacht_id, yi.id AS image_id, yi.url, yi.external_url, yi."position", yi.main_image, yi.hidden, yi.layout_override
            FROM yacht_image yi
            WHERE yi.yacht_id IN (:ids) AND yi.url IS NOT NULL AND yi.url <> '' AND NOT yi.hidden
        """
        private const val UPDATE_SQL = "UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id = ?"
        private const val REPICK_SQL = "UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id IS NOT DISTINCT FROM ?"
        private const val FLAG_SQL =
            "UPDATE yacht_image SET main_image = (id IS NOT DISTINCT FROM ?) WHERE yacht_id = ? AND (main_image OR id IS NOT DISTINCT FROM ?)"
        private val UPDATE_TYPES = intArrayOf(Types.BIGINT, Types.BIGINT, Types.BIGINT)
    }
}
