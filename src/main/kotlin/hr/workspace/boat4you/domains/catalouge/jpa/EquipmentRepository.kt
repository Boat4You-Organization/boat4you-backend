package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EquipmentRepository : JpaRepository<Equipment, Long> {
    /**
     * The whole catalogue in id order, alias rows included (callers skip `mergedIntoId != null` where needed). In memory
     * 10 h on each node; a restart refreshes it. The sync matcher relies on the id order (ties go to the lower id).
     */
    @Cacheable("equipmentCache")
    fun findAllByOrderByIdAsc(): List<Equipment>

    /** Admin catalogue: alias rows left out. */
    fun findAllByMergedIntoIdIsNull(pageable: Pageable): Page<Equipment>

    @Cacheable("equipmentFilter")
    @Query(
        """
        SELECT e FROM Equipment e
        WHERE e.filterOrder IS NOT NULL
        ORDER BY e.filterOrder 
    """,
    )
    fun findForFilters(): List<Equipment>
}
