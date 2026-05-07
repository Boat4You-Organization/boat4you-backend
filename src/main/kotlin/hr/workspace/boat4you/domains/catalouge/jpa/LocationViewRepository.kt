package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface LocationViewRepository : JpaRepository<LocationView, Long> {
    @Query("SELECT lv FROM LocationView lv WHERE lv.id IN :ids")
    fun findByIds(ids: List<String>): List<LocationView>

    @Query(
        """
    SELECT lv 
        FROM LocationView lv 
        WHERE LOWER(lv.searchFiled) LIKE LOWER(CONCAT('%', :name, '%'))
        AND (:ids IS NULL OR lv.id NOT IN :ids)
        ORDER BY CASE lv.locationType
            WHEN 'COUNTRY' THEN 1
            WHEN 'REGION' THEN 2
            WHEN 'MARINA' THEN 3
            ELSE 4
        END, lv.name
    """,
    )
    fun findByNameAndIdsNotIn(
        @Param("name") name: String,
        ids: List<String>?,
        pageable: Pageable,
    ): Page<LocationView>

    @Query("SELECT lv FROM LocationView lv WHERE lv.locationType = 'COUNTRY'")
    fun getCountries(): List<LocationView>

    @Query(
        """SELECT lv FROM LocationView lv
        WHERE lv.locationType = 'REGION'
        AND lv.countryCode = :countryCode
        """,
    )
    fun getRegions(countryCode: String): List<LocationView>

    @Query(
        """SELECT lv FROM LocationView lv
        WHERE lv.locationType = 'MARINA'
        AND lv.countryCode = :countryCode
        ORDER BY lv.name ASC
        """,
    )
    fun getMarinas(countryCode: String): List<LocationView>

    @Query(
        """
        SELECT new hr.workspace.boat4you.domains.catalouge.jpa.ExternalLocationDto(lv.realId, lv.countryCode, em.externalId, em.externalSystem.id, lv.locationType)
        FROM LocationView lv
        JOIN ExternalMapping em ON em.systemId = lv.realId AND em.type = :externalType
        WHERE lv.id = :id
    """,
    )
    fun findExternalIdById(
        id: String,
        externalType: String,
    ): List<ExternalLocationDto>

    @Query(
        """
        SELECT lv
        FROM LocationView lv
        WHERE (:ids IS NULL OR lv.id NOT IN :ids)
    """,
    )
    fun findAllAndIdsNotIn(
        ids: List<String>?,
        pageable: Pageable,
    ): Page<LocationView>
}

data class ExternalLocationDto(
    val systemId: Long,
    val countryCode: String,
    val externalId: Long,
    val externalSystemId: Int,
    val locationType: LocationType,
)
