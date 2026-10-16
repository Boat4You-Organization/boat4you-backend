package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CatalogueImageRepository : JpaRepository<CatalogueImage, Long> {
    /** Active MARINA photos of any of [locationIds], in gallery order: position, then the older photo first. */
    @Query(value = ACTIVE_BY_LOCATION_IDS_SQL, nativeQuery = true)
    fun findActiveByLocationIds(
        @Param("locationIds") locationIds: Collection<Long>,
    ): List<CatalogueImage>

    /** Every photo of one location row, active or not (admin list), in gallery order. */
    fun findByLocationIdOrderByPositionAscIdAsc(locationId: Long): List<CatalogueImage>

    /**
     * The rows paired with [locationId] in location_same_place (V9_67 / V9_77-V9_79: one physical marina imported once
     * per partner), in either direction - one hop, as the search reads the curated pairs.
     */
    @Query(value = SAME_PLACE_PARTNER_IDS_SQL, nativeQuery = true)
    fun findSamePlacePartnerIds(
        @Param("locationId") locationId: Long,
    ): List<Long>

    companion object {
        const val ACTIVE_BY_LOCATION_IDS_SQL = """
            SELECT ci.* FROM catalogue_image ci
            WHERE ci.active AND ci.kind = 'MARINA' AND ci.location_id IN (:locationIds)
            ORDER BY ci."position", ci.id
        """

        const val SAME_PLACE_PARTNER_IDS_SQL = """
            SELECT sp.same_as_location_id FROM location_same_place sp WHERE sp.location_id = :locationId
            UNION
            SELECT sp.location_id FROM location_same_place sp WHERE sp.same_as_location_id = :locationId
        """
    }
}
