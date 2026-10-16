package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet

/** The boat whose photos stand in for a model ([yachtId] null: no boat of the model qualifies). Cached, never null. */
data class ModelDonor(
    val yachtId: Long?,
)

/**
 * The donor of model photos for a boat without a real photo (photos DESIGN.md, Mario 16.10.2026): another served boat
 * of the same model_id - sys_active, and a CUSTOM boat or one of an active, unblocked agency (getValidYacht's test) -
 * with at least [MIN_DONOR_PHOTOS] stored photos that are not layout drawings ([LayoutImageRules] or the row's
 * layout_override, read in Kotlin, so the SQL only pre-selects on the stored-image count) and not hidden (V9_91).
 * Preferred: a boat based in the same country, then the most photos, then the lowest id (stable). The caller decides
 * whether the model is generic.
 *
 * The choice is cached per model and country ('modelDonorCache', 10 min, CacheConfig); the photos themselves are read
 * fresh ([donorPhotoIds]), so a photo a partner sync removed in the meantime is not served.
 */
@Component
class ModelDonorLookup(
    private val jdbc: JdbcTemplate,
) {
    @Cacheable(cacheNames = ["modelDonorCache"], key = "#modelId + ':' + #countryCode")
    fun donorFor(
        modelId: Long,
        countryCode: String?,
    ): ModelDonor {
        val images =
            jdbc.query(
                CANDIDATE_IMAGES_SQL,
                RowMapper { rs, _ ->
                    CandidateImage(
                        yachtId = rs.getLong("yacht_id"),
                        sameCountry = rs.getBoolean("same_country"),
                        photo = !isLayout(rs),
                    )
                },
                countryCode,
                modelId,
            )
        val donor =
            images
                .groupBy { it.yachtId }
                .map { (yachtId, rows) -> Candidate(yachtId, rows.first().sameCountry, rows.count { it.photo }) }
                .filter { it.photos >= MIN_DONOR_PHOTOS }
                .minWithOrNull(compareByDescending<Candidate> { it.sameCountry }.thenByDescending { it.photos }.thenBy { it.yachtId })
        return ModelDonor(donor?.yachtId)
    }

    /** The stored photos of [yachtId] that are not layout drawings, in gallery order (position, then id). */
    fun donorPhotoIds(yachtId: Long): List<Long> =
        jdbc
            .query(
                DONOR_IMAGES_SQL,
                RowMapper { rs, _ -> rs.getLong("id") to isLayout(rs) },
                yachtId,
            ).filterNot { (_, layout) -> layout }
            .map { (id, _) -> id }

    /** yacht_image.layout_override (V9_91) when set, else the file name - MainImageRules.isLayout on a row. */
    private fun isLayout(rs: ResultSet): Boolean =
        rs.getBoolean("layout_override").takeUnless { rs.wasNull() } ?: LayoutImageRules.isLayout(rs.getString("external_url"), rs.getString("url"))

    private data class CandidateImage(
        val yachtId: Long,
        val sameCountry: Boolean,
        val photo: Boolean,
    )

    private data class Candidate(
        val yachtId: Long,
        val sameCountry: Boolean,
        val photos: Int,
    )

    companion object {
        const val MIN_DONOR_PHOTOS = 4

        /** Candidates pre-selected in SQL; a big model is cut to the [CANDIDATE_LIMIT] boats with the most stored images. */
        const val CANDIDATE_LIMIT = 20

        /** Params: the boat's country code (may be null), the model id. */
        const val CANDIDATE_IMAGES_SQL = """
            WITH cand AS (
                SELECT y.id, COALESCE(l.country_code = CAST(? AS varchar), false) AS same_country, count(*) AS stored
                FROM yacht y
                JOIN yacht_image yi ON yi.yacht_id = y.id AND yi.url IS NOT NULL AND yi.url <> '' AND NOT yi.hidden
                LEFT JOIN location l ON l.id = y.location_id
                LEFT JOIN agency a ON a.id = y.agency_id
                WHERE y.model_id = ?
                  AND y.sys_active
                  AND (y.entry_type = 'CUSTOM'
                       OR (y.entry_type = 'EXTERNAL' AND a.active AND NOT a.availability_blocked))
                GROUP BY y.id, l.country_code
                HAVING count(*) >= $MIN_DONOR_PHOTOS
                ORDER BY same_country DESC, stored DESC, y.id
                LIMIT $CANDIDATE_LIMIT
            )
            SELECT c.id AS yacht_id, c.same_country, yi.url, yi.external_url, yi.layout_override
            FROM cand c
            JOIN yacht_image yi ON yi.yacht_id = c.id AND yi.url IS NOT NULL AND yi.url <> '' AND NOT yi.hidden
        """

        /** Param: the donor yacht id. */
        const val DONOR_IMAGES_SQL = """
            SELECT yi.id, yi.url, yi.external_url, yi.layout_override
            FROM yacht_image yi
            WHERE yi.yacht_id = ? AND yi.url IS NOT NULL AND yi.url <> '' AND NOT yi.hidden
            ORDER BY yi."position" NULLS LAST, yi.id
        """
    }
}
