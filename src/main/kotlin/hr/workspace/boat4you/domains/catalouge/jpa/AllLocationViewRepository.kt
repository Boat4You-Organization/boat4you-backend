package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AllLocationViewRepository : JpaRepository<AllLocationView, String> {
    @Query(
        """
        SELECT a FROM AllLocationView a 
        WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :name, '%')) AND a.locationType = :locationType
    """,
    )
    fun findAllByNameLikeAndLocationType(
        name: String,
        locationType: LocationType,
        pageable: Pageable,
    ): Page<AllLocationView>

    fun findAllByLocationType(
        locationType: LocationType,
        pageable: Pageable,
    ): Page<AllLocationView>
}
