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

/**
 * One-off repair behind POST /admin/yachts/main-image/fix-layout (16.10.2026: 11 offered boats showed their layout drawing
 * on the search card although they have photos). For every active boat whose main_image_id is a layout drawing
 * ([MainImageRules.isLayout]) and that has a stored photo, main_image_id becomes the photo the fixed sync would pick
 * ([MainImageRules.pick]: the partner's main photo, else the lowest position), so the next sync keeps it. Only stored
 * images (url set) qualify - the card serves the file. A boat with no photo is left alone and listed. Each UPDATE re-checks
 * the old value, so a sync that moved the main meanwhile wins. The yacht_content_modified trigger (V9_71) moves the
 * lastmod of the boats changed; the cards follow at the next yacht_search_view refresh.
 */
@Service
class YachtMainImageFixService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val named = NamedParameterJdbcTemplate(jdbc)

    private class Main(
        val yachtId: Long,
        val image: YachtImage,
    )

    @Transactional
    fun fixLayoutMainImages(dryRun: Boolean): MainImageFixResultDto {
        val layoutMains =
            jdbc
                .query(MAINS_SQL) { rs, _ -> Main(rs.getLong("yacht_id"), image(rs)) }
                .filter { MainImageRules.isLayout(it.image) }
        if (layoutMains.isEmpty()) return MainImageFixResultDto(dryRun, 0, emptyList(), emptyList())

        val storedByYacht =
            named
                .query(IMAGES_SQL, mapOf("ids" to layoutMains.map { it.yachtId })) { rs, _ -> rs.getLong("yacht_id") to image(rs) }
                .groupBy({ it.first }, { it.second })
        val planned = mutableListOf<MainImageFixDto>()
        val withoutPhoto = mutableListOf<Long>()
        layoutMains.forEach { main ->
            val photo =
                MainImageRules.pick(storedByYacht[main.yachtId].orEmpty(), main.image.id)?.takeUnless(MainImageRules::isLayout)
            if (photo == null) {
                withoutPhoto += main.yachtId
            } else {
                planned += MainImageFixDto(main.yachtId, main.image.id!!, photo.id!!)
            }
        }
        val fixed =
            if (dryRun) {
                planned
            } else {
                planned.filter { jdbc.update(UPDATE_SQL, it.toImageId, it.yachtId, it.fromImageId) == 1 }
            }
        log.info(
            "Layout main image fix (dryRun={}): {} boats with a layout main, {} {} {}, {} without a photo",
            dryRun,
            layoutMains.size,
            fixed.size,
            if (dryRun) "to fix" else "fixed",
            fixed.map { it.yachtId },
            withoutPhoto.size,
        )
        return MainImageFixResultDto(dryRun, layoutMains.size, fixed, withoutPhoto)
    }

    private fun image(rs: ResultSet) =
        YachtImage().apply {
            id = rs.getLong("image_id")
            url = rs.getString("url")
            externalUrl = rs.getString("external_url")
            position = rs.getShort("position").takeUnless { rs.wasNull() }
            mainImage = rs.getBoolean("main_image").takeUnless { rs.wasNull() }
        }

    companion object {
        private const val MAINS_SQL = """
            SELECT y.id AS yacht_id, yi.id AS image_id, yi.url, yi.external_url, yi."position", yi.main_image
            FROM yacht y
            JOIN yacht_image yi ON yi.id = y.main_image_id AND yi.yacht_id = y.id
            WHERE y.sys_active
            ORDER BY y.id
        """
        private const val IMAGES_SQL = """
            SELECT yi.yacht_id, yi.id AS image_id, yi.url, yi.external_url, yi."position", yi.main_image
            FROM yacht_image yi
            WHERE yi.yacht_id IN (:ids) AND yi.url IS NOT NULL AND yi.url <> ''
        """
        private const val UPDATE_SQL = "UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id = ?"
    }
}
