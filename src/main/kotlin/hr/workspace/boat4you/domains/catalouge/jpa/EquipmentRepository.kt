package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EquipmentRepository : JpaRepository<Equipment, Long> {
    @Cacheable("equipmentCache")
    override fun findAll(): List<Equipment>

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
